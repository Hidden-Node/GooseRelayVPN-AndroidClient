// Package tun — gvisor_bridge_test.go
//
// Portable unit tests for the gVisor bridge helpers (state, filter, handshake
// and file logger). No build tag: the whole file compiles and runs on every
// platform, following the portable-test structure of
// gvisor_target_guard_test.go (table-driven cases, hand-built wire messages,
// no external DNS library).
// ==============================================================================
package tun

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"os"
	"path/filepath"
	"regexp"
	"strconv"
	"strings"
	"testing"
	"time"
)

// ── Test message builders (hand-rolled wire format) ──────────────────────────

// gvisorTestQuery builds a minimal DNS query message for name with qtype.
func gvisorTestQuery(t *testing.T, name string, qtype uint16) []byte {
	t.Helper()
	msg := []byte{0x12, 0x34, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for _, label := range gvisorTestLabels(name) {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0x00)
	msg = binary.BigEndian.AppendUint16(msg, qtype)
	msg = binary.BigEndian.AppendUint16(msg, 1) // class IN
	return msg
}

// gvisorTestLabels splits a dotted name into labels without the stdlib DNS
// helpers (there are none — this keeps the builders dependency-free).
func gvisorTestLabels(name string) []string {
	var out []string
	start := 0
	for i := 0; i <= len(name); i++ {
		if i == len(name) || name[i] == '.' {
			if i > start {
				out = append(out, name[start:i])
			}
			start = i + 1
		}
	}
	return out
}

// gvisorTestAnswer appends one uncompressed-owner answer record of rtype to a
// response message under construction.
func gvisorTestAnswer(msg []byte, name string, rtype uint16, rdata []byte) []byte {
	for _, label := range gvisorTestLabels(name) {
		msg = append(msg, byte(len(label)))
		msg = append(msg, label...)
	}
	msg = append(msg, 0x00)
	msg = binary.BigEndian.AppendUint16(msg, rtype)
	msg = binary.BigEndian.AppendUint16(msg, 1)   // class IN
	msg = binary.BigEndian.AppendUint32(msg, 300) // TTL
	msg = binary.BigEndian.AppendUint16(msg, uint16(len(rdata)))
	return append(msg, rdata...)
}

// gvisorTestCounts overwrites the ANCOUNT/NSCOUNT/ARCOUNT header fields.
func gvisorTestCounts(msg []byte, an, ns, ar uint16) {
	binary.BigEndian.PutUint16(msg[6:8], an)
	binary.BigEndian.PutUint16(msg[8:10], ns)
	binary.BigEndian.PutUint16(msg[10:12], ar)
}

// ── AAAA filter ───────────────────────────────────────────────────────────────

func TestGvisorAAAAFilter(t *testing.T) {
	t.Run("aaaa_only_drops_answers_other_counts_unchanged", func(t *testing.T) {
		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81 // QR=1, RD=1
		resp[3] = 0x80 // RA=1
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		gvisorTestCounts(resp, 2, 0, 0)

		got := gvisorFilterDNSAAAA(resp)
		if an := binary.BigEndian.Uint16(got[6:8]); an != 0 {
			t.Fatalf("expected ANCOUNT=0 after dropping AAAA answers, got %d", an)
		}
		if ns := binary.BigEndian.Uint16(got[8:10]); ns != 0 {
			t.Fatalf("expected NSCOUNT unchanged at 0, got %d", ns)
		}
		if ar := binary.BigEndian.Uint16(got[10:12]); ar != 0 {
			t.Fatalf("expected ARCOUNT unchanged at 0, got %d", ar)
		}
		// No AAAA record may remain in the wire stream.
		aaaas := 0
		p := 12
		for p+10 <= len(got) {
			rdLen := int(binary.BigEndian.Uint16(got[p+8 : p+10]))
			if typ := binary.BigEndian.Uint16(got[p+2 : p+4]); typ == gvisorDnsTypeAAAA {
				aaaas++
			}
			p += 10 + rdLen
		}
		if aaaas != 0 {
			t.Fatalf("expected 0 AAAA records after filtering, found %d", aaaas)
		}
	})

	t.Run("a_only_response_returned_identical", func(t *testing.T) {
		resp := gvisorTestQuery(t, "example.com", 1)
		resp[2] = 0x81
		resp[3] = 0x80
		resp = gvisorTestAnswer(resp, "example.com", 1, []byte{93, 184, 216, 34})
		gvisorTestCounts(resp, 1, 0, 0)

		got := gvisorFilterDNSAAAA(resp)
		// Zero-copy fast path: same backing array, not a rebuilt message.
		if &got[0] != &resp[0] {
			t.Fatal("expected AAAA-free response to be returned on the zero-copy path")
		}
		if len(got) != len(resp) {
			t.Fatalf("length changed: got=%d orig=%d", len(got), len(resp))
		}
		if string(got) != string(resp) {
			t.Fatal("A-only response content changed")
		}
	})

	t.Run("query_qr0_untouched", func(t *testing.T) {
		query := gvisorTestQuery(t, "example.com", 28)
		got := gvisorFilterDNSAAAA(query)
		if &got[0] != &query[0] {
			t.Fatal("expected query (QR=0) to be returned untouched")
		}
	})

	t.Run("malformed_untouched", func(t *testing.T) {
		// Shorter than the fixed DNS header.
		short := []byte{0x81, 0x80, 0, 1, 0, 1, 0, 0, 0, 0}
		if got := gvisorFilterDNSAAAA(short); &got[0] != &short[0] {
			t.Fatal("expected truncated message to be returned unchanged")
		}
		// Answer whose rdlength claims more bytes than the message holds.
		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81
		resp[3] = 0x80
		resp = gvisorTestAnswer(resp, "example.com", 28, make([]byte, 16))
		gvisorTestCounts(resp, 1, 0, 0)
		resp = resp[:len(resp)-8] // truncate into the AAAA rdata
		if got := gvisorFilterDNSAAAA(resp); &got[0] != &resp[0] {
			t.Fatal("expected malformed record stream to be returned unchanged")
		}
	})

	t.Run("toggle_off_keeps_aaaa", func(t *testing.T) {
		restore := GetGvisorDisableIPv6()
		defer func() { SetGvisorDisableIPv6(restore) }()

		SetGvisorDisableIPv6(false)
		if GetGvisorDisableIPv6() {
			t.Fatal("SetGvisorDisableIPv6(false) must disable blocking")
		}

		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81
		resp[3] = 0x80
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		resp = gvisorTestAnswer(resp, "example.com", 1, []byte{93, 184, 216, 34})
		gvisorTestCounts(resp, 2, 0, 0)

		got := gvisorFilterDNSAAAA(resp)
		if &got[0] != &resp[0] {
			t.Fatal("toggle OFF: response must pass through unmodified (zero-copy)")
		}

		SetGvisorDisableIPv6(true)
		if !GetGvisorDisableIPv6() {
			t.Fatal("SetGvisorDisableIPv6(true) must restore blocking")
		}
	})

	t.Run("empty_leading_sections_attribute_to_ar", func(t *testing.T) {
		// AN=0 NS=0 AR=2 (e.g. an OPT-carrying AAAA response): a kept A
		// record followed by a dropped AAAA record. The section cursor must
		// skip ALL empty leading sections before attributing records, or the
		// A record is misattributed into NSCOUNT.
		resp := gvisorTestQuery(t, "example.com", 28)
		resp[2] = 0x81 // QR=1, RD=1 — REQUIRED: the filter passes non-responses through untouched
		resp[3] = 0x80 // RA=1
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01
		resp = gvisorTestAnswer(resp, "example.com", 1, []byte{93, 184, 216, 34})
		resp = gvisorTestAnswer(resp, "example.com", 28, v6)
		gvisorTestCounts(resp, 0, 0, 2) // AN=0 NS=0 AR=2

		got := gvisorFilterDNSAAAA(resp)
		if an := binary.BigEndian.Uint16(got[6:8]); an != 0 {
			t.Fatalf("expected ANCOUNT=0 after dropping the AAAA answer, got %d", an)
		}
		ns := binary.BigEndian.Uint16(got[8:10])
		ar := binary.BigEndian.Uint16(got[10:12])
		if ns != 0 || ar != 1 {
			t.Fatalf("expected NSCOUNT=0 ARCOUNT=1 after skipping empty leading sections, got NS=%d AR=%d", ns, ar)
		}
		// Survivors: exactly one type-1 (A) record, zero AAAA records.
		qEnd, ok := gvisorDNSQuestionEnd(got)
		if !ok {
			t.Fatal("rebuilt message question unparseable")
		}
		countA, countAAAA := 0, 0
		p := qEnd
		for p+10 <= len(got) {
			nameLen, ok := gvisorDNSNameLengthAt(got, p)
			if !ok {
				t.Fatal("rebuilt record stream unparseable")
			}
			typ := binary.BigEndian.Uint16(got[p+nameLen : p+nameLen+2])
			rdLen := int(binary.BigEndian.Uint16(got[p+nameLen+8 : p+nameLen+10]))
			switch typ {
			case 1:
				countA++
			case gvisorDnsTypeAAAA:
				countAAAA++
			}
			p += nameLen + 10 + rdLen
		}
		if countA != 1 || countAAAA != 0 {
			t.Fatalf("expected exactly one A record and zero AAAA records surviving, got A=%d AAAA=%d", countA, countAAAA)
		}
	})

	t.Run("record_fixed_header_truncation_returns_original", func(t *testing.T) {
		resp := gvisorTestQuery(t, "a", 28)
		resp[2] = 0x81 // QR=1, RD=1
		resp[3] = 0x80 // RA=1
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01
		resp = gvisorTestAnswer(resp, "a", 28, v6)
		gvisorTestCounts(resp, 1, 0, 0)
		resp = resp[:19+10]                 // keep exactly p+10 record bytes: name(3)+type(2)+class(2)+3 of 4 TTL bytes
		capExact := make([]byte, len(resp)) // compaction REQUIRED: helpers' append backing keeps the full cap
		copy(capExact, resp)
		resp = capExact
		got := gvisorFilterDNSAAAA(resp)
		if string(got) != string(resp) {
			t.Fatalf("truncated fixed header must return the original message, got %d/%d bytes", len(got), len(resp))
		}
	})

	t.Run("count_stream_mismatch_returns_original", func(t *testing.T) {
		q := gvisorTestQuery(t, "example.com", 28)
		q[2] = 0x81 // QR=1, RD=1
		q[3] = 0x80 // RA=1
		v6 := make([]byte, 16)
		v6[0] = 0x20
		v6[1] = 0x01

		// Case A: counts overstate the stream — ANCOUNT=2, the wire holds one A record.
		respA := append([]byte(nil), gvisorTestAnswer(q, "example.com", 1, []byte{93, 184, 216, 34})...)
		gvisorTestCounts(respA, 2, 0, 0)
		gotA := gvisorFilterDNSAAAA(respA)
		if string(gotA) != string(respA) {
			t.Fatalf("counts overstating the stream must return the original, got %d/%d bytes", len(gotA), len(respA))
		}

		// Case B: counts fully matched, plus 5 unattributed tail bytes and a AAAA inside the counted records.
		respB := gvisorTestAnswer(q, "example.com", 1, []byte{93, 184, 216, 34}) // A record
		respB = gvisorTestAnswer(respB, "example.com", 28, v6)                   // AAAA record
		gvisorTestCounts(respB, 2, 0, 0)
		respB = append(respB, 0x00, 0x01, 0x02, 0x03, 0x04) // 5 unattributed tail bytes
		gotB := gvisorFilterDNSAAAA(respB)
		if string(gotB) != string(respB) {
			t.Fatalf("bytes beyond the header counts must return the original, got %d/%d bytes", len(gotB), len(respB))
		}
	})
}

// ── SOCKS5 handshake (over net.Pipe) ─────────────────────────────────────────

type gvisorTestSocksResult struct {
	creds  string // "user/pass" as received by the far end ("" when no auth)
	target string // CONNECT target as received ("" when never reached)
	err    string // far-end failure label ("" on success)
}

// gvisorTestPipeServer drives the far end of a net.Pipe through a SOCKS5
// handshake script. methodSelection is the byte the server answers the
// greeting with (0x00 no-auth, 0x02 USER_PASS). expectUser/expectPass are the
// credentials the server requires (authOK picks matched/mismatched); when
// credentials are requested but prove missing, authOK=false aborts with the
// auth-status byte. connectReply is written verbatim as the CONNECT reply.
func gvisorTestPipeServer(pipe net.Conn, methodSelection byte, authOK bool, expectUser, expectPass string, connectReply []byte, out chan<- gvisorTestSocksResult) {
	defer func() { _ = pipe.Close() }()
	res := gvisorTestSocksResult{}

	greet := make([]byte, 3)
	if n, err := io.ReadFull(pipe, greet); n != 3 || err != nil {
		res.err = fmt.Sprintf("greeting: read %d: %v", n, err)
		out <- res
		return
	}
	if greet[0] != 0x05 || greet[1] != 0x01 {
		res.err = fmt.Sprintf("greeting: ver=%d nmeth=%d", greet[0], greet[1])
		out <- res
		return
	}
	if greet[2] != 0x00 && greet[2] != 0x02 {
		res.err = fmt.Sprintf("greeting: unexpected method offered 0x%02x", greet[2])
		out <- res
		return
	}
	if _, werr := pipe.Write([]byte{0x05, methodSelection}); werr != nil {
		res.err = "method write: " + werr.Error()
		out <- res
		return
	}

	if methodSelection == 0x02 {
		var ver [1]byte
		if _, err := io.ReadFull(pipe, ver[:]); err != nil {
			res.err = "auth ver: " + err.Error()
			out <- res
			return
		}
		var ulen [1]byte
		if _, err := io.ReadFull(pipe, ulen[:]); err != nil {
			res.err = "auth ulen: " + err.Error()
			out <- res
			return
		}
		ub := make([]byte, int(ulen[0]))
		if _, err := io.ReadFull(pipe, ub); err != nil {
			res.err = "auth user: " + err.Error()
			out <- res
			return
		}
		var plen [1]byte
		if _, err := io.ReadFull(pipe, plen[:]); err != nil {
			res.err = "auth plen: " + err.Error()
			out <- res
			return
		}
		pb := make([]byte, int(plen[0]))
		if _, err := io.ReadFull(pipe, pb); err != nil {
			res.err = "auth pass: " + err.Error()
			out <- res
			return
		}
		res.creds = string(ub) + "/" + string(pb)
		if string(ub) != expectUser || string(pb) != expectPass {
			_, _ = pipe.Write([]byte{0x01, 0x01})
			out <- res
			return
		}
		if _, err := pipe.Write([]byte{0x01, 0x00}); err != nil {
			res.err = "auth reply write: " + err.Error()
			out <- res
			return
		}
	}

	// CONNECT: VER CMD RSV ATYP then address then port.
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(pipe, hdr); err != nil {
		res.err = "connect hdr: " + err.Error()
		out <- res
		return
	}
	var host string
	switch hdr[3] {
	case 0x01:
		ip := make([]byte, 4)
		if _, err := io.ReadFull(pipe, ip); err != nil {
			res.err = "connect v4: " + err.Error()
			out <- res
			return
		}
		host = net.IP(ip).String()
	case 0x03:
		lb := make([]byte, 1)
		if _, err := io.ReadFull(pipe, lb); err != nil {
			res.err = "connect domlen: " + err.Error()
			out <- res
			return
		}
		dn := make([]byte, int(lb[0]))
		if _, err := io.ReadFull(pipe, dn); err != nil {
			res.err = "connect domain: " + err.Error()
			out <- res
			return
		}
		host = string(dn)
	case 0x04:
		ip := make([]byte, 16)
		if _, err := io.ReadFull(pipe, ip); err != nil {
			res.err = "connect v6: " + err.Error()
			out <- res
			return
		}
		host = net.IP(ip).String()
	default:
		res.err = fmt.Sprintf("connect atyp unknown: %d", hdr[3])
		out <- res
		return
	}
	portBuf := make([]byte, 2)
	if _, err := io.ReadFull(pipe, portBuf); err != nil {
		res.err = "connect port: " + err.Error()
		out <- res
		return
	}
	port := int(binary.BigEndian.Uint16(portBuf))
	res.target = net.JoinHostPort(host, strconv.Itoa(port))

	if _, werr := pipe.Write(connectReply); werr != nil {
		res.err = "connect reply write: " + werr.Error()
		out <- res
		return
	}
	out <- res
}

// gvisorTestRunHandshake connects both pipe halves, launches the far-end
// script and exercises the bridge handshake; returns the client error, the
// far-end result snapshot and a function that releases both halves.
func gvisorTestRunHandshake(t *testing.T, wrapAuth *gvisorSocksCred, methodSelection byte, authOK bool, expectUser, expectPass, connectTarget string, connectReply []byte) (error, gvisorTestSocksResult) {
	t.Helper()
	clientPipe, serverPipe := net.Pipe()
	t.Cleanup(func() {
		_ = clientPipe.Close()
		_ = serverPipe.Close()
	})
	out := make(chan gvisorTestSocksResult, 1)
	go gvisorTestPipeServer(serverPipe, methodSelection, authOK, expectUser, expectPass, connectReply, out)

	var conn net.Conn = clientPipe
	if wrapAuth != nil {
		conn = &gvisorSocksAuthConn{Conn: clientPipe, cred: *wrapAuth}
	}
	clientErr := gvisorSocks5ConnectTCP(conn, connectTarget, 443)
	// Handshake is over (success or refusal) — release both halves now so a
	// far-end write the client never drained (refusal mid-reply) unblocks
	// immediately instead of waiting for t.Cleanup.
	_ = clientPipe.Close()
	_ = serverPipe.Close()

	select {
	case res := <-out:
		return clientErr, res
	case <-time.After(3 * time.Second):
		t.Fatalf("pipe server script did not complete (clientErr=%v)", clientErr)
		return nil, gvisorTestSocksResult{}
	}
}

func TestGvisorSocks5Handshake(t *testing.T) {
	successReply := []byte{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0}

	t.Run("no_auth_connect_succeeds", func(t *testing.T) {
		clientErr, res := gvisorTestRunHandshake(t, nil, 0x00, true, "", "", "example.com", successReply)
		if clientErr != nil {
			t.Fatalf("expected success, got error: %v", clientErr)
		}
		if res.err != "" {
			t.Fatalf("unexpected far-end failure: %q", res.err)
		}
		if res.creds != "" {
			t.Fatalf("no-auth flow must not send credentials, got %q", res.creds)
		}
		if res.target != "example.com:443" {
			t.Fatalf("unexpected CONNECT target: %q", res.target)
		}
	})

	t.Run("user_pass_subnegotiation_succeeds", func(t *testing.T) {
		clientErr, res := gvisorTestRunHandshake(t, &gvisorSocksCred{user: "u", pass: "p"}, 0x02, true, "u", "p", "example.com", successReply)
		if clientErr != nil {
			t.Fatalf("expected success, got error: %v", clientErr)
		}
		if res.err != "" {
			t.Fatalf("unexpected far-end failure: %q", res.err)
		}
		if res.creds != "u/p" {
			t.Fatalf("credentials not forwarded: got %q", res.creds)
		}
		if res.target != "example.com:443" {
			t.Fatalf("CONNECT target lost after auth: %q", res.target)
		}
	})

	t.Run("wrong_password_errors", func(t *testing.T) {
		clientErr, res := gvisorTestRunHandshake(t, &gvisorSocksCred{user: "u", pass: "WRONG"}, 0x02, true, "u", "p", "example.com", successReply)
		if clientErr == nil {
			t.Fatal("expected error on wrong password, got nil")
		}
		if !strings.Contains(clientErr.Error(), "rejected") {
			t.Fatalf("error should mention auth rejection, got: %v", clientErr)
		}
		if res.creds != "u/WRONG" {
			t.Fatalf("expected far end to receive mismatched creds, got %q", res.creds)
		}
	})

	t.Run("connect_refused_code3_errors", func(t *testing.T) {
		refusedReply := []byte{0x05, 0x03, 0x00, 0x01, 0, 0, 0, 0, 0, 0}
		clientErr, res := gvisorTestRunHandshake(t, nil, 0x00, true, "", "", "192.0.2.44", refusedReply)
		if clientErr == nil {
			t.Fatal("expected error on code=3 refusal, got nil")
		}
		if !strings.Contains(clientErr.Error(), "code=3") {
			t.Fatalf("error should mention code=3, got: %v", clientErr)
		}
		if res.target != "192.0.2.44:443" {
			t.Fatalf("unexpected CONNECT target at far end: %q", res.target)
		}
	})

	t.Run("fragmented_method_selection_still_authenticates", func(t *testing.T) {
		clientPipe, serverPipe := net.Pipe()
		t.Cleanup(func() {
			_ = clientPipe.Close()
			_ = serverPipe.Close()
		})
		out := make(chan gvisorTestSocksResult, 1)
		go func() {
			defer func() { _ = serverPipe.Close() }()
			res := gvisorTestSocksResult{}

			// Greeting read + validation, mirroring gvisorTestPipeServer.
			greet := make([]byte, 3)
			if n, err := io.ReadFull(serverPipe, greet); n != 3 || err != nil {
				res.err = fmt.Sprintf("greeting: read %d: %v", n, err)
				out <- res
				return
			}
			if greet[0] != 0x05 || greet[1] != 0x01 {
				res.err = fmt.Sprintf("greeting: ver=%d nmeth=%d", greet[0], greet[1])
				out <- res
				return
			}
			if greet[2] != 0x00 && greet[2] != 0x02 {
				res.err = fmt.Sprintf("greeting: unexpected method offered 0x%02x", greet[2])
				out <- res
				return
			}

			// Fragment the method selection across two separate single-byte
			// writes — legal TCP segmentation (e.g. two loopback segments).
			if _, err := serverPipe.Write([]byte{0x05}); err != nil {
				res.err = "method write: " + err.Error()
				out <- res
				return
			}
			if _, err := serverPipe.Write([]byte{0x02}); err != nil {
				res.err = "method write: " + err.Error()
				out <- res
				return
			}

			// USER_PASS sub-negotiation, mirroring gvisorTestPipeServer.
			var ver [1]byte
			if _, err := io.ReadFull(serverPipe, ver[:]); err != nil {
				res.err = "auth ver: " + err.Error()
				out <- res
				return
			}
			var ulen [1]byte
			if _, err := io.ReadFull(serverPipe, ulen[:]); err != nil {
				res.err = "auth ulen: " + err.Error()
				out <- res
				return
			}
			ub := make([]byte, int(ulen[0]))
			if _, err := io.ReadFull(serverPipe, ub); err != nil {
				res.err = "auth user: " + err.Error()
				out <- res
				return
			}
			var plen [1]byte
			if _, err := io.ReadFull(serverPipe, plen[:]); err != nil {
				res.err = "auth plen: " + err.Error()
				out <- res
				return
			}
			pb := make([]byte, int(plen[0]))
			if _, err := io.ReadFull(serverPipe, pb); err != nil {
				res.err = "auth pass: " + err.Error()
				out <- res
				return
			}
			res.creds = string(ub) + "/" + string(pb)
			if string(ub) != "u" || string(pb) != "p" {
				_, _ = serverPipe.Write([]byte{0x01, 0x01})
				out <- res
				return
			}
			if _, err := serverPipe.Write([]byte{0x01, 0x00}); err != nil {
				res.err = "auth reply write: " + err.Error()
				out <- res
				return
			}

			// CONNECT: VER CMD RSV ATYP then address then port.
			hdr := make([]byte, 4)
			if _, err := io.ReadFull(serverPipe, hdr); err != nil {
				res.err = "connect hdr: " + err.Error()
				out <- res
				return
			}
			if hdr[3] != 0x03 {
				res.err = fmt.Sprintf("connect atyp: %d", hdr[3])
				out <- res
				return
			}
			lb := make([]byte, 1)
			if _, err := io.ReadFull(serverPipe, lb); err != nil {
				res.err = "connect domlen: " + err.Error()
				out <- res
				return
			}
			dn := make([]byte, int(lb[0]))
			if _, err := io.ReadFull(serverPipe, dn); err != nil {
				res.err = "connect domain: " + err.Error()
				out <- res
				return
			}
			portBuf := make([]byte, 2)
			if _, err := io.ReadFull(serverPipe, portBuf); err != nil {
				res.err = "connect port: " + err.Error()
				out <- res
				return
			}
			res.target = net.JoinHostPort(string(dn), strconv.Itoa(int(binary.BigEndian.Uint16(portBuf))))

			if _, werr := serverPipe.Write(successReply); werr != nil {
				res.err = "connect reply write: " + werr.Error()
				out <- res
				return
			}
			out <- res
		}()

		conn := &gvisorSocksAuthConn{Conn: clientPipe, cred: gvisorSocksCred{user: "u", pass: "p"}}
		clientErr := gvisorSocks5ConnectTCP(conn, "example.com", 443)
		_ = clientPipe.Close()
		_ = serverPipe.Close()

		select {
		case res := <-out:
			if clientErr != nil {
				t.Fatalf("expected success with fragmented method selection, got error: %v", clientErr)
			}
			if res.err != "" {
				t.Fatalf("unexpected far-end failure: %q", res.err)
			}
			if res.creds != "u/p" {
				t.Fatalf("credentials not forwarded: got %q", res.creds)
			}
			if res.target != "example.com:443" {
				t.Fatalf("unexpected CONNECT target: %q", res.target)
			}
		case <-time.After(3 * time.Second):
			t.Fatalf("pipe server script did not complete (clientErr=%v)", clientErr)
		}
	})

	t.Run("domain_exceeds_255_bytes_errors", func(t *testing.T) {
		longDomain := strings.Repeat("a", 256)
		clientErr, _ := gvisorTestRunHandshake(t, nil, 0x00, true, "", "", longDomain, successReply)
		if clientErr == nil {
			t.Fatal("expected connect error for domain > 255 bytes, got nil")
		}
		if !strings.Contains(clientErr.Error(), "socks5 connect: invalid target domain (empty or exceeds 255 bytes)") {
			t.Fatalf("unexpected error message: %v", clientErr)
		}
	})
}

type gvisorTestHalfCloseConn struct {
	net.Conn
	closeWriteCalled bool
	closeCalled      bool
}

func (c *gvisorTestHalfCloseConn) CloseWrite() error {
	c.closeWriteCalled = true
	return nil
}

func (c *gvisorTestHalfCloseConn) Close() error {
	c.closeCalled = true
	if c.Conn != nil {
		return c.Conn.Close()
	}
	return nil
}

type gvisorTestPlainConn struct {
	net.Conn
	closeCalled bool
}

func (c *gvisorTestPlainConn) Close() error {
	c.closeCalled = true
	if c.Conn != nil {
		return c.Conn.Close()
	}
	return nil
}

func TestGvisorSocksAuthConnCloseWrite(t *testing.T) {
	t.Run("delegates_to_underlying_half_closer", func(t *testing.T) {
		underlying := &gvisorTestHalfCloseConn{}
		authConn := &gvisorSocksAuthConn{Conn: underlying}

		hc, ok := any(authConn).(interface{ CloseWrite() error })
		if !ok {
			t.Fatal("expected *gvisorSocksAuthConn to implement CloseWrite()")
		}
		if err := hc.CloseWrite(); err != nil {
			t.Fatalf("unexpected error from CloseWrite(): %v", err)
		}
		if !underlying.closeWriteCalled {
			t.Fatal("expected underlying CloseWrite() to be called")
		}
		if underlying.closeCalled {
			t.Fatal("underlying Close() should not be called when CloseWrite is supported")
		}
	})

	t.Run("falls_back_to_close_when_unsupported", func(t *testing.T) {
		underlying := &gvisorTestPlainConn{}
		authConn := &gvisorSocksAuthConn{Conn: underlying}

		if err := authConn.CloseWrite(); err != nil {
			t.Fatalf("unexpected error from CloseWrite() fallback: %v", err)
		}
		if !underlying.closeCalled {
			t.Fatal("expected underlying Close() fallback to be called")
		}
	})
}

func TestGvisorSocks5ConnectRequest(t *testing.T) {
	t.Run("ipv4_ten_bytes_atyp1", func(t *testing.T) {
		req := gvisorBuildSocks5ConnectRequest("192.0.2.10", 443)
		if len(req) != 10 {
			t.Fatalf("expected 10-byte IPv4 request, got %d bytes: % x", len(req), req)
		}
		if req[3] != 0x01 {
			t.Fatalf("expected ATYP=0x01, got 0x%02x", req[3])
		}
		want := [...]byte{192, 0, 2, 10}
		if got := req[4:8]; string(got) != string(want[:]) {
			t.Fatalf("wrong address bytes: % x", got)
		}
		if binary.BigEndian.Uint16(req[8:10]) != 443 {
			t.Fatalf("wrong port: %d", binary.BigEndian.Uint16(req[8:10]))
		}
	})

	t.Run("domain_atyp3_with_length_byte", func(t *testing.T) {
		req := gvisorBuildSocks5ConnectRequest("example.com", 443)
		if len(req) != 7+len("example.com") {
			t.Fatalf("expected %d-byte domain request, got %d bytes", 7+len("example.com"), len(req))
		}
		if req[3] != 0x03 {
			t.Fatalf("expected ATYP=0x03, got 0x%02x", req[3])
		}
		if req[4] != byte(len("example.com")) {
			t.Fatalf("wrong domain length byte: %d", req[4])
		}
		if string(req[5:5+len("example.com")]) != "example.com" {
			t.Fatalf("wrong domain bytes: %q", string(req[5:5+len("example.com")]))
		}
		if binary.BigEndian.Uint16(req[5+len("example.com"):]) != 443 {
			t.Fatalf("wrong port: %d", binary.BigEndian.Uint16(req[5+len("example.com"):]))
		}
	})

	t.Run("ipv6_twentytwo_bytes_atyp4", func(t *testing.T) {
		req := gvisorBuildSocks5ConnectRequest("2001:db8::1", 443)
		if len(req) != 22 {
			t.Fatalf("expected 22-byte IPv6 request, got %d bytes", len(req))
		}
		if req[3] != 0x04 {
			t.Fatalf("expected ATYP=0x04, got 0x%02x", req[3])
		}
		want := net.ParseIP("2001:db8::1").To16()
		if !bytesEqual(req[4:20], want) {
			t.Fatalf("wrong address bytes: % x vs % x", req[4:20], want)
		}
		if binary.BigEndian.Uint16(req[20:22]) != 443 {
			t.Fatalf("wrong port: %d", binary.BigEndian.Uint16(req[20:22]))
		}
	})

	t.Run("empty_domain_returns_nil", func(t *testing.T) {
		if req := gvisorBuildSocks5ConnectRequest("", 443); req != nil {
			t.Fatalf("expected nil for empty domain, got %d bytes", len(req))
		}
	})

	t.Run("domain_exceeds_255_bytes_returns_nil", func(t *testing.T) {
		longDomain := strings.Repeat("a", 256)
		if req := gvisorBuildSocks5ConnectRequest(longDomain, 443); req != nil {
			t.Fatalf("expected nil for domain > 255 bytes, got %d bytes", len(req))
		}
	})
}

func bytesEqual(a, b []byte) bool {
	return len(a) == len(b) && string(a) == string(b)
}

// ── Poison-guard target normalization ────────────────────────────────────────

func TestGvisorTargetGuardNormalization(t *testing.T) {
	t.Run("v4_mapped_v6_poison_target_refused", func(t *testing.T) {
		// The virtual stack can surface an IPv4 destination as a v4-mapped
		// IPv6 literal; normalization must collapse it to the plain IPv4
		// form BEFORE the shared guard runs, or the poison address inside
		// 10/8 would slip through to a dial.
		host := gvisorNormalizeTargetHost("::ffff:10.10.34.35")
		if host != "10.10.34.35" {
			t.Fatalf("expected v4-mapped v6 to normalize to 10.10.34.35, got %q", host)
		}
		if !gvisorIsBlockedSOCKSTarget(host) {
			t.Fatal("poison IP 10.10.34.35 must be refused after normalization")
		}

		// Sanity: a global IPv4 destination normalizes unchanged and is
		// allowed through, and a global IPv6 literal is left alone.
		if got := gvisorNormalizeTargetHost("93.184.216.34"); got != "93.184.216.34" {
			t.Fatalf("global IPv4 must not be rewritten, got %q", got)
		}
		if gvisorIsBlockedSOCKSTarget("93.184.216.34") {
			t.Fatal("global IPv4 must not be refused")
		}
		if got := gvisorNormalizeTargetHost("2001:db8::1"); got != "2001:db8::1" {
			t.Fatalf("native IPv6 must pass through normalization unchanged, got %q", got)
		}
	})
}

// ── File logger (portable) ────────────────────────────────────────────────────

func TestGvisorFileLog(t *testing.T) {
	t.Run("writes_all_severities_with_viewer_prefix", func(t *testing.T) {
		path := filepath.Join(t.TempDir(), "bridge.log")
		if err := gvisorLogOpen(path); err != nil {
			t.Fatalf("open: %v", err)
		}

		gvisorLogLine(gvisorLogInfo, "test info line abc123")
		gvisorLogLine(gvisorLogWarn, "test warn line def456")
		gvisorLogLine(gvisorLogError, "test error line ghi789")
		gvisorLogClose()

		data, err := os.ReadFile(path)
		if err != nil {
			t.Fatalf("read back: %v", err)
		}
		lines := strings.Split(strings.TrimRight(string(data), "\n"), "\n")
		if len(lines) != 3 {
			t.Fatalf("expected exactly 3 lines, got %d:\n%s", len(lines), data)
		}

		// Shape, not the clock: YYYY-MM-DD HH:MM:SS [TUN-GVISOR] [SEVERITY] …
		shape := regexp.MustCompile(`^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} \[TUN-GVISOR\] \[(INFO|WARN|ERROR)\] .+`)
		for i, wantSev := range []string{"INFO", "WARN", "ERROR"} {
			if !shape.MatchString(lines[i]) {
				t.Fatalf("line %d does not match required shape: %q", i, lines[i])
			}
			if !strings.Contains(lines[i], "[TUN-GVISOR] ["+wantSev+"]") {
				t.Fatalf("line %d missing %s severity: %q", i, wantSev, lines[i])
			}
		}
		for i, wantMsg := range []string{"test info line abc123", "test warn line def456", "test error line ghi789"} {
			if !strings.Contains(lines[i], wantMsg) {
				t.Fatalf("line %d missing message %q: %q", i, wantMsg, lines[i])
			}
		}

		// Disabled logger mode: empty path is a no-op, never an error.
		if err := gvisorLogOpen(""); err != nil {
			t.Fatalf("empty logPath must disable the logger, got error: %v", err)
		}
		gvisorLogClose()
	})
}

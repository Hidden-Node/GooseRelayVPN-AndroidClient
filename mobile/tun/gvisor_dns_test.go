// Package tun — gvisor_dns_test.go
//
// Portable unit tests for the in-process fake-IP DNS mapper and responder.
// Exercises stable mapping, response formatting (A, NODATA, SERVFAIL),
// EDNS0 handling, malformed query rejection, LRU/retention with a simulated clock,
// mapTarget translations, and guard interaction.
// ==============================================================================
package tun

import (
	"encoding/binary"
	"net"
	"strings"
	"testing"
	"time"
)

func TestGvisorDNSMapper_StableMapping(t *testing.T) {
	mapper := newGvisorDNSMapper()

	// 1. Stable mapping: two A queries for the same name return the same fake IP;
	// two different names return different IPs; every answer is inside 240.0.0.0/4
	// and outside 240.0.0.0 / 255.255.255.255.
	q1 := gvisorTestQuery(t, "example.com", 1)
	resp1, ok1 := mapper.answer(q1)
	if !ok1 {
		t.Fatalf("expected query 1 to succeed")
	}

	resp2, ok2 := mapper.answer(q1)
	if !ok2 {
		t.Fatalf("expected query 2 for same name to succeed")
	}

	ip1 := extractIPFromAResponse(t, resp1)
	ip2 := extractIPFromAResponse(t, resp2)
	if !ip1.Equal(ip2) {
		t.Fatalf("expected same IP for same name, got %s and %s", ip1, ip2)
	}

	qDiff := gvisorTestQuery(t, "other.com", 1)
	respDiff, okDiff := mapper.answer(qDiff)
	if !okDiff {
		t.Fatalf("expected query for other name to succeed")
	}
	ipDiff := extractIPFromAResponse(t, respDiff)
	if ipDiff.Equal(ip1) {
		t.Fatalf("expected different IPs for different names, both got %s", ipDiff)
	}

	for _, ip := range []net.IP{ip1, ip2, ipDiff} {
		v4 := ip.To4()
		if v4 == nil {
			t.Fatalf("expected IPv4 address, got %s", ip)
		}
		val := binary.BigEndian.Uint32(v4)
		if val&gvisorDNSPoolMask != gvisorDNSPoolBase {
			t.Fatalf("IP %s not in 240.0.0.0/4", ip)
		}
		if val == gvisorDNSPoolBase || val == 0xFFFFFFFF {
			t.Fatalf("IP %s is boundary forbidden address (240.0.0.0 or 255.255.255.255)", ip)
		}
	}
}

func TestGvisorDNSMapper_AResponseWellFormed(t *testing.T) {
	mapper := newGvisorDNSMapper()
	q := gvisorTestQuery(t, "api.example.com", 1)
	q[0], q[1] = 0xAA, 0xBB // ID
	q[2] = 0x01             // RD=1

	resp, ok := mapper.answer(q)
	if !ok {
		t.Fatalf("answer failed")
	}

	// ID echoed
	if resp[0] != 0xAA || resp[1] != 0xBB {
		t.Fatalf("ID not echoed: got 0x%02x%02x", resp[0], resp[1])
	}

	// Flags: QR=1 (0x8000), RD=1 (0x0100), RA=1 (0x0080) -> 0x8180, RCODE=0
	flags := binary.BigEndian.Uint16(resp[2:4])
	if flags != 0x8180 {
		t.Fatalf("expected flags 0x8180, got 0x%04x", flags)
	}

	qdcount := binary.BigEndian.Uint16(resp[4:6])
	ancount := binary.BigEndian.Uint16(resp[6:8])
	nscount := binary.BigEndian.Uint16(resp[8:10])
	arcount := binary.BigEndian.Uint16(resp[10:12])
	if qdcount != 1 || ancount != 1 || nscount != 0 || arcount != 0 {
		t.Fatalf("wrong counts: QD=%d AN=%d NS=%d AR=%d", qdcount, ancount, nscount, arcount)
	}

	qEnd, ok := gvisorDNSQuestionEnd(resp)
	if !ok {
		t.Fatalf("could not parse question from response")
	}
	// Question echoed exactly once
	if string(resp[12:qEnd]) != string(q[12:]) {
		t.Fatalf("question section mismatch")
	}

	// Answer section:
	ans := resp[qEnd:]
	if len(ans) != 16 {
		t.Fatalf("expected 16 answer bytes, got %d", len(ans))
	}
	// Pointer 0xC0 0x0C
	if ans[0] != 0xC0 || ans[1] != 0x0C {
		t.Fatalf("expected pointer 0xC00C, got 0x%02x%02x", ans[0], ans[1])
	}
	typ := binary.BigEndian.Uint16(ans[2:4])
	class := binary.BigEndian.Uint16(ans[4:6])
	ttl := binary.BigEndian.Uint32(ans[6:10])
	rdlen := binary.BigEndian.Uint16(ans[10:12])
	if typ != 1 || class != 1 {
		t.Fatalf("expected TYPE=1 CLASS=1, got TYPE=%d CLASS=%d", typ, class)
	}
	if ttl != 30 {
		t.Fatalf("expected TTL=30, got %d", ttl)
	}
	if rdlen != 4 {
		t.Fatalf("expected RDLENGTH=4, got %d", rdlen)
	}
}

func TestGvisorDNSMapper_NODATAForAAAAAndOther(t *testing.T) {
	mapper := newGvisorDNSMapper()

	cases := []struct {
		name  string
		qtype uint16
		class uint16
	}{
		{"AAAA", 28, 1},
		{"HTTPS", 65, 1},
		{"PTR", 12, 1},
		{"QCLASS_CH", 1, 3},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			q := []byte{0x45, 0x67, 0x01, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
			for _, label := range gvisorTestLabels("example.com") {
				q = append(q, byte(len(label)))
				q = append(q, label...)
			}
			q = append(q, 0x00)
			q = binary.BigEndian.AppendUint16(q, tc.qtype)
			q = binary.BigEndian.AppendUint16(q, tc.class)

			resp, ok := mapper.answer(q)
			if !ok {
				t.Fatalf("expected NODATA answer to succeed")
			}
			flags := binary.BigEndian.Uint16(resp[2:4])
			rcode := flags & 0x000F
			if rcode != 0 {
				t.Fatalf("expected RCODE=0, got %d", rcode)
			}
			ancount := binary.BigEndian.Uint16(resp[6:8])
			if ancount != 0 {
				t.Fatalf("expected ANCOUNT=0, got %d", ancount)
			}
			qEnd, ok := gvisorDNSQuestionEnd(resp)
			if !ok {
				t.Fatalf("question unparseable")
			}
			if len(resp) != qEnd {
				t.Fatalf("expected no answer/additional section, got len=%d qEnd=%d", len(resp), qEnd)
			}
		})
	}
}

func TestGvisorDNSMapper_EDNS0NotCopied(t *testing.T) {
	mapper := newGvisorDNSMapper()
	q := gvisorTestQuery(t, "example.com", 1)

	// Set ARCOUNT = 1
	binary.BigEndian.PutUint16(q[10:12], 1)

	// Append EDNS0 OPT record: Name(0), TYPE(41), PayloadSize(4096), ExtRCODE/Flags(0), RDLEN(0)
	optRecord := []byte{
		0x00,       // root name
		0x00, 0x29, // TYPE 41 (OPT)
		0x10, 0x00, // UDP payload size 4096
		0x00, 0x00, 0x00, 0x00, // ExtRCODE + flags
		0x00, 0x00, // RDLENGTH 0
	}
	q = append(q, optRecord...)

	resp, ok := mapper.answer(q)
	if !ok {
		t.Fatalf("answer failed on query with EDNS0")
	}

	if len(resp) >= 512 {
		t.Fatalf("expected response < 512 bytes, got %d", len(resp))
	}
	arcount := binary.BigEndian.Uint16(resp[10:12])
	if arcount != 0 {
		t.Fatalf("expected ARCOUNT=0, got %d", arcount)
	}

	qEnd, ok := gvisorDNSQuestionEnd(resp)
	if !ok {
		t.Fatalf("question unparseable")
	}
	// Total response is header (12) + question (qEnd - 12) + 16 bytes A answer.
	if len(resp) != qEnd+16 {
		t.Fatalf("response has unexpected trailing bytes: len=%d, want=%d", len(resp), qEnd+16)
	}
}

func TestGvisorDNSMapper_MalformedQueriesDropped(t *testing.T) {
	mapper := newGvisorDNSMapper()

	// Valid base query
	valid := gvisorTestQuery(t, "valid.com", 1)

	// Label > 63 bytes (64 'a's)
	label64 := strings.Repeat("a", 64)
	queryLabel64 := []byte{0x12, 0x34, 0x00, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 64}
	queryLabel64 = append(queryLabel64, []byte(label64)...)
	queryLabel64 = append(queryLabel64, 0, 0, 1, 0, 1)

	// Name > 255 bytes (4 labels of 63 bytes + 1 label of 10 bytes)
	queryName256 := []byte{0x12, 0x34, 0x00, 0x00, 0, 1, 0, 0, 0, 0, 0, 0}
	for i := 0; i < 4; i++ {
		queryName256 = append(queryName256, 63)
		queryName256 = append(queryName256, []byte(strings.Repeat("b", 63))...)
	}
	queryName256 = append(queryName256, 10)
	queryName256 = append(queryName256, []byte(strings.Repeat("c", 10))...)
	queryName256 = append(queryName256, 0, 0, 1, 0, 1)

	// Truncated QTYPE: valid QNAME followed by only 1 byte
	queryTruncQtype := []byte{0x12, 0x34, 0x00, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 1, 'a', 0, 1}

	// Compression pointer in QNAME (0xC0 0x0C at offset 12)
	queryCompPtr := []byte{0x12, 0x34, 0x00, 0x00, 0, 1, 0, 0, 0, 0, 0, 0, 0xC0, 0x0C, 0, 1, 0, 1}

	cases := []struct {
		name  string
		query []byte
	}{
		{"short_header", []byte{0x12, 0x34, 0x00, 0x00, 0, 1}},
		{"qdcount_0", func() []byte {
			b := append([]byte(nil), valid...)
			binary.BigEndian.PutUint16(b[4:6], 0)
			return b
		}()},
		{"qdcount_2", func() []byte {
			b := append([]byte(nil), valid...)
			binary.BigEndian.PutUint16(b[4:6], 2)
			return b
		}()},
		{"qr_1", func() []byte {
			b := append([]byte(nil), valid...)
			b[2] |= 0x80
			return b
		}()},
		{"opcode_1", func() []byte {
			b := append([]byte(nil), valid...)
			b[2] |= 0x08
			return b
		}()},
		{"compression_pointer_in_qname", queryCompPtr},
		{"label_length_gt_63", queryLabel64},
		{"name_gt_255", queryName256},
		{"truncated_qtype", queryTruncQtype},
	}

	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			resp, ok := mapper.answer(tc.query)
			if ok || resp != nil {
				t.Fatalf("expected query to be rejected as malformed, got ok=%v resp=%v", ok, resp)
			}
		})
	}
}

func TestGvisorDNSMapper_LRUAndRetention(t *testing.T) {
	baseTime := time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	currTime := baseTime

	mapper := newGvisorDNSMapper()
	mapper.now = func() time.Time { return currTime }

	qA := gvisorTestQuery(t, "a.com", 1)
	respA1, ok := mapper.answer(qA)
	if !ok {
		t.Fatalf("query failed")
	}
	ipA1 := extractIPFromAResponse(t, respA1)

	// Still mapped after 9 minutes
	currTime = baseTime.Add(9 * time.Minute)
	respA2, ok := mapper.answer(qA)
	if !ok {
		t.Fatalf("query failed at 9m")
	}
	ipA2 := extractIPFromAResponse(t, respA2)
	if !ipA2.Equal(ipA1) {
		t.Fatalf("mapping changed after 9 minutes: %s vs %s", ipA1, ipA2)
	}

	// 11 minutes of inactivity after the 9m query (total 20m)
	currTime = currTime.Add(11 * time.Minute)
	respA3, ok := mapper.answer(qA)
	if !ok {
		t.Fatalf("query failed at 20m")
	}
	ipA3 := extractIPFromAResponse(t, respA3)
	if ipA3.Equal(ipA1) {
		t.Fatalf("expected new IP after 11m expiry, got same %s", ipA3)
	}

	// Max capacity test: mapper.max = 3
	mapper.max = 3
	// Currently has "a.com" mapped at currTime. Add 2 more: "b.com", "c.com"
	qB := gvisorTestQuery(t, "b.com", 1)
	qC := gvisorTestQuery(t, "c.com", 1)
	qD := gvisorTestQuery(t, "d.com", 1)

	respB, okB := mapper.answer(qB)
	respC, okC := mapper.answer(qC)
	if !okB || !okC {
		t.Fatalf("failed to add b and c")
	}
	ipB := extractIPFromAResponse(t, respB)
	ipC := extractIPFromAResponse(t, respC)

	// Map is full of young entries (a, b, c). 4th entry "d.com" must receive SERVFAIL (RCODE=2, ANCOUNT=0)
	respD, okD := mapper.answer(qD)
	if !okD {
		t.Fatalf("expected answer function to return true for well-formed query even if full")
	}
	flagsD := binary.BigEndian.Uint16(respD[2:4])
	if rcode := flagsD & 0x000F; rcode != 2 {
		t.Fatalf("expected SERVFAIL (RCODE=2) on full map, got %d", rcode)
	}
	if an := binary.BigEndian.Uint16(respD[6:8]); an != 0 {
		t.Fatalf("expected ANCOUNT=0 on SERVFAIL, got %d", an)
	}

	// Confirm valid entries were not evicted
	respACheck, _ := mapper.answer(qA)
	respBCheck, _ := mapper.answer(qB)
	respCCheck, _ := mapper.answer(qC)
	if !extractIPFromAResponse(t, respACheck).Equal(ipA3) ||
		!extractIPFromAResponse(t, respBCheck).Equal(ipB) ||
		!extractIPFromAResponse(t, respCCheck).Equal(ipC) {
		t.Fatalf("existing entries were evicted despite being young")
	}

	// Advance time past retention (11 minutes) so entries expire
	currTime = currTime.Add(11 * time.Minute)
	// Now new entry "d.com" must prune expired entries and succeed
	respDAfterPrune, ok := mapper.answer(qD)
	if !ok {
		t.Fatalf("d.com query failed after pruning")
	}
	flagsDPrune := binary.BigEndian.Uint16(respDAfterPrune[2:4])
	if rcode := flagsDPrune & 0x000F; rcode != 0 {
		t.Fatalf("expected NOERROR after pruning, got RCODE=%d", rcode)
	}
	if an := binary.BigEndian.Uint16(respDAfterPrune[6:8]); an != 1 {
		t.Fatalf("expected ANCOUNT=1 after pruning, got %d", an)
	}
}

func TestGvisorDNSMapper_MapTarget(t *testing.T) {
	mapper := newGvisorDNSMapper()

	// Query Example.COM
	q := gvisorTestQuery(t, "Example.COM", 1)
	resp, ok := mapper.answer(q)
	if !ok {
		t.Fatalf("query failed")
	}
	fakeIP := extractIPFromAResponse(t, resp).String()

	// 1. Mapped fake IP -> lowercase domain
	target, ok := mapper.mapTarget(fakeIP)
	if !ok || target != "example.com" {
		t.Fatalf("expected example.com, got %q (ok=%v)", target, ok)
	}

	// 2. Unmapped in-pool IP -> ("", false)
	target, ok = mapper.mapTarget("240.10.20.30")
	if ok || target != "" {
		t.Fatalf("expected unmapped in-pool IP to return (\"\", false), got %q (ok=%v)", target, ok)
	}

	// 3. Normal host -> unchanged, true
	target, ok = mapper.mapTarget("93.184.216.34")
	if !ok || target != "93.184.216.34" {
		t.Fatalf("expected normal IPv4 unchanged, got %q (ok=%v)", target, ok)
	}

	// 4. IPv6 or non-IP host -> unchanged, true
	target, ok = mapper.mapTarget("2001:db8::1")
	if !ok || target != "2001:db8::1" {
		t.Fatalf("expected IPv6 unchanged, got %q (ok=%v)", target, ok)
	}
	target, ok = mapper.mapTarget("already-a-domain.org")
	if !ok || target != "already-a-domain.org" {
		t.Fatalf("expected domain host unchanged, got %q (ok=%v)", target, ok)
	}
}

func TestGvisorDNSMapper_GuardOrder(t *testing.T) {
	mapper := newGvisorDNSMapper()

	q := gvisorTestQuery(t, "api.google.com", 1)
	resp, ok := mapper.answer(q)
	if !ok {
		t.Fatalf("query failed")
	}
	fakeIP := extractIPFromAResponse(t, resp).String()

	// Note: the TCP path in gvisorProxyTCPDirect calls mapper.mapTarget before
	// gvisorIsBlockedSOCKSTarget.
	target, ok := mapper.mapTarget(fakeIP)
	if !ok {
		t.Fatalf("expected fake IP to be mapped")
	}
	if gvisorIsBlockedSOCKSTarget(target) {
		t.Fatalf("mapped target %q should not be blocked", target)
	}

	// Meanwhile 198.18.0.2 is still blocked by the guard
	if !gvisorIsBlockedSOCKSTarget("198.18.0.2") {
		t.Fatalf("198.18.0.2 must be blocked by the guard")
	}
}

func extractIPFromAResponse(t *testing.T, resp []byte) net.IP {
	t.Helper()
	if len(resp) < 16 {
		t.Fatalf("response too short: %d", len(resp))
	}
	// Last 4 bytes of A response is the IP
	return net.IP(resp[len(resp)-4:])
}

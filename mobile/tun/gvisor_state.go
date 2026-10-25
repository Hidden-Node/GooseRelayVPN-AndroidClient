// Package tun — gvisor_state.go
//
// Shared state of the gVisor TUN bridge, kept free of build tags so the
// AAAA filter can be unit-tested on every platform. The platform API lives
// in gvisor_api.go (linux||android) and gvisor_stub.go (others).
// ==============================================================================
package tun

import (
	"encoding/binary"
	"sync"
)

var (
	gvisorStateMu sync.Mutex

	// gvisorDisableIPv6 gates IPv6 blocking in the bridge. Default true:
	// the tunnel has no IPv6 egress, so AAAA answers (and IPv6 CONNECTs)
	// only produce `socks5 connect refused code 3` timeouts in apps that
	// race their dual-stack connect. No settings UI is wired to this yet;
	// SetGvisorDisableIPv6 is a test seam (see the filter tests) until a
	// dedicated plan adds the toggle.
	gvisorDisableIPv6 = true
)

// SetGvisorDisableIPv6 toggles the bridge's IPv6 blocking. Currently a
// test seam only (no settings UI calls it — IPv6 blocking is always on
// in production); safe to call at any time, affects queries/connections
// from now on.
func SetGvisorDisableIPv6(disabled bool) {
	gvisorStateMu.Lock()
	gvisorDisableIPv6 = disabled
	gvisorStateMu.Unlock()
}

// GetGvisorDisableIPv6 reports whether IPv6 blocking is currently enabled.
func GetGvisorDisableIPv6() bool {
	gvisorStateMu.Lock()
	defer gvisorStateMu.Unlock()
	return gvisorDisableIPv6
}

// gvisorIPv6Blocked reports whether IPv6 traffic must be rejected by the bridge.
func gvisorIPv6Blocked() bool {
	gvisorStateMu.Lock()
	defer gvisorStateMu.Unlock()
	return gvisorDisableIPv6
}

// ── DNS AAAA filtering (IPv6 fix for TUN mode) ─────────────────────────────

// gvisorDnsHeaderLen is the fixed size of a DNS message header:
// ID(2) Flags(2) QDCOUNT(2) ANCOUNT(2) NSCOUNT(2) ARCOUNT(2).
const gvisorDnsHeaderLen = 12

// gvisorDnsTypeAAAA is the DNS record type for IPv6 addresses (RFC 3596).
const gvisorDnsTypeAAAA = 28

// gvisorFilterDNSAAAA removes every AAAA record from a DNS response message.
//
// The tunnel can only carry IPv4 connectivity: the remote side answers every
// IPv6 CONNECT with SOCKS5 NETWORK_UNREACHABLE (code=3), which the bridge
// turns into a TCP RST — the app then shows ERR_CONNECTION_RESET. Android
// prefers IPv6 whenever a AAAA answer exists (RFC 6724), so advertising AAAA
// records guarantees broken first attempts for every dual-stack site.
// Rewriting AAAA replies to NOERROR/NODATA makes the resolver fall back to
// the A record immediately, which works end-to-end.
//
// This is the same mitigation used by clash/sing-box tun stacks.
// Unlike sing-box, which rebuilds record names uncompressed, kept
// records here are copied byte-verbatim: any DNS name compression
// pointing into the answer/additional region can resolve into
// shifted/garbage bytes after a drop. Owner names compressed against
// the question — the common case — stay valid (the header and question
// sections are copied at identical offsets).
//
// Blocked by default (the tunnel has no IPv6 egress); there is no settings
// toggle yet, only the in-package SetGvisorDisableIPv6 test seam. When
// blocking is off, responses pass through unmodified and the IPv6 TCP
// fast-RST in the bridge is also disabled.
//
// The function returns the original slice untouched when the message is not
// a response carrying AAAA answers, so the common IPv4 path stays zero-copy.
func gvisorFilterDNSAAAA(resp []byte) []byte {
	if !gvisorIPv6Blocked() {
		return resp
	}
	if len(resp) < gvisorDnsHeaderLen {
		return resp
	}
	flags := binary.BigEndian.Uint16(resp[2:4])
	if flags&0x8000 == 0 {
		return resp // QR=0: a query, not a response — leave untouched.
	}
	hasRecords := false
	for _, off := range []int{6, 8, 10} { // ANCOUNT, NSCOUNT, ARCOUNT
		if binary.BigEndian.Uint16(resp[off:off+2]) > 0 {
			hasRecords = true
		}
	}
	if !hasRecords {
		return resp
	}

	// Parse the question section to find where the answer records start.
	qEnd, ok := gvisorDNSQuestionEnd(resp)
	if !ok {
		return resp
	}

	// Walk the AN+NS+AR sections keeping only non-AAAA records.
	origAN := int(binary.BigEndian.Uint16(resp[6:8]))
	origNS := int(binary.BigEndian.Uint16(resp[8:10]))
	// Records in the wire stream appear in AN, then NS, then AR order.
	// We only need to detect AAAA records inside them; owner-name parsing is
	// unnecessary because every record's fixed part (type/class/ttl/rdlen)
	// follows the variable-length name. Instead of parsing names we rebuild
	// from parsed records: each record's name is resolved by scanning with
	// the name-length helper, which handles compression pointers.
	p := qEnd
	total := origAN + origNS + int(binary.BigEndian.Uint16(resp[10:12]))
	type rec struct {
		start, end int
		isAAAA     bool
	}
	recs := make([]rec, 0, total)
	for i := 0; i < total && p+10 <= len(resp); i++ {
		nameLen, ok := gvisorDNSNameLengthAt(resp, p)
		if !ok {
			return resp // malformed — safer to keep the original message.
		}
		if p+nameLen+10 > len(resp) {
			return resp
		}
		rtype := binary.BigEndian.Uint16(resp[p+nameLen : p+nameLen+2])
		rdLen := int(binary.BigEndian.Uint16(resp[p+nameLen+8 : p+nameLen+10]))
		end := p + nameLen + 10 + rdLen
		if end > len(resp) {
			return resp
		}
		recs = append(recs, rec{start: p, end: end, isAAAA: rtype == gvisorDnsTypeAAAA})
		p = end
	}
	if len(recs) != total || p != len(resp) {
		return resp
	}

	// Build output: header + question + kept records, then fix the counts.
	kept := 0
	out := make([]byte, 0, len(resp))
	out = append(out, resp[:qEnd]...)
	sectionKept := [3]int{0, 0, 0}
	section := 0
	sectionRemaining := origAN
	for _, r := range recs {
		for sectionRemaining == 0 && section < 2 {
			section++
			switch section {
			case 1:
				sectionRemaining = origNS
			case 2:
				sectionRemaining = int(binary.BigEndian.Uint16(resp[10:12]))
			}
		}
		if sectionRemaining > 0 {
			sectionRemaining--
		}
		if r.isAAAA {
			continue
		}
		sectionKept[section]++
		kept++
		out = append(out, resp[r.start:r.end]...)
	}
	if kept == total {
		return resp // nothing dropped — return the original (zero-copy fast path).
	}
	binary.BigEndian.PutUint16(out[6:8], uint16(sectionKept[0]))
	binary.BigEndian.PutUint16(out[8:10], uint16(sectionKept[1]))
	binary.BigEndian.PutUint16(out[10:12], uint16(sectionKept[2]))
	return out
}

// gvisorDNSQuestionEnd returns the offset just past the first question entry
// (QNAME + QTYPE + QCLASS), handling uncompressed and compressed labels.
func gvisorDNSQuestionEnd(msg []byte) (int, bool) {
	nameLen, ok := gvisorDNSNameLengthAt(msg, gvisorDnsHeaderLen)
	if !ok {
		return 0, false
	}
	q := gvisorDnsHeaderLen + nameLen + 4 // + QTYPE + QCLASS
	if q > len(msg) {
		return 0, false
	}
	return q, true
}

// gvisorDNSNameLengthAt returns the byte length of the (possibly compressed)
// domain name starting at off in a DNS message, not counting any bytes
// reached through compression pointers beyond their 2-byte header.
func gvisorDNSNameLengthAt(msg []byte, off int) (int, bool) {
	p := off
	for {
		if p >= len(msg) {
			return 0, false
		}
		l := int(msg[p])
		if l == 0 {
			return p + 1 - off, true
		}
		if l&0xC0 == 0xC0 {
			if p+2 > len(msg) {
				return 0, false
			}
			return p + 2 - off, true
		}
		p += 1 + l
	}
}

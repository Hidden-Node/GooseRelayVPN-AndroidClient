// Package tun — gvisor_dns.go
//
// In-process fake-IP DNS mapper and responder for the gVisor TUN bridge.
// Intercepts UDP/53 queries in-process and answers them locally without
// tunnelling any DNS packets. A queries receive synthetic IPv4 addresses
// from 240.0.0.0/4; AAAA and other qtypes receive NOERROR/NODATA responses.
// When TCP connections arrive for a fake IP, the TCP forwarder translates the
// fake IP back to its domain name before the SOCKS5 CONNECT.
//
// This file is portable (no build tag) so it is unit-testable on all platforms.
// ==============================================================================
package tun

import (
	"container/list"
	"encoding/binary"
	"net"
	"strings"
	"sync"
	"time"
)

const (
	gvisorDNSMapTTL       = 30 * time.Second // TTL written into the A answer
	gvisorDNSMapRetention = 10 * time.Minute // mapping lifetime after last use
	gvisorDNSMapMax       = 32768            // hard cap on live names

	gvisorDNSPoolBase  uint32 = 240 << 24 // 240.0.0.0/4
	gvisorDNSPoolMask  uint32 = 0xF0000000
	gvisorDNSPoolFirst uint32 = gvisorDNSPoolBase | 1 // 240.0.0.1
	gvisorDNSPoolLast  uint32 = 0xFFFFFFFE            // 255.255.255.254
)

// gvisorDNSMapper maps names to fake IPv4s and back. The zero value is NOT
// usable; call newGvisorDNSMapper.
type gvisorDNSMapper struct {
	mu     sync.Mutex
	byName map[string]*gvisorDNSMapEntry
	byIP   map[uint32]*gvisorDNSMapEntry
	lru    *list.List       // front = most recently used; values are *gvisorDNSMapEntry
	next   uint32           // last allocated address; 0 = none yet
	max    int              // gvisorDNSMapMax; tests may lower it
	now    func() time.Time // test seam; nil means time.Now
}

type gvisorDNSMapEntry struct {
	name    string
	ip      uint32
	expires time.Time
	elem    *list.Element
}

func newGvisorDNSMapper() *gvisorDNSMapper {
	return &gvisorDNSMapper{
		byName: make(map[string]*gvisorDNSMapEntry),
		byIP:   make(map[uint32]*gvisorDNSMapEntry),
		lru:    list.New(),
		max:    gvisorDNSMapMax,
	}
}

func (m *gvisorDNSMapper) clock() time.Time {
	if m.now != nil {
		return m.now()
	}
	return time.Now()
}

func (m *gvisorDNSMapper) deleteEntry(e *gvisorDNSMapEntry) {
	delete(m.byName, e.name)
	delete(m.byIP, e.ip)
	if e.elem != nil {
		m.lru.Remove(e.elem)
		e.elem = nil
	}
}

func (m *gvisorDNSMapper) lookupOrAssign(name string) (uint32, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := m.clock()
	if e, exists := m.byName[name]; exists {
		if !now.After(e.expires) {
			e.expires = now.Add(gvisorDNSMapRetention)
			m.lru.MoveToFront(e.elem)
			return e.ip, true
		}
		m.deleteEntry(e)
	}

	for elem := m.lru.Back(); elem != nil; {
		prev := elem.Prev()
		entry := elem.Value.(*gvisorDNSMapEntry)
		if now.After(entry.expires) {
			m.deleteEntry(entry)
			elem = prev
		} else {
			break
		}
	}

	maxCap := m.max
	if maxCap <= 0 {
		maxCap = gvisorDNSMapMax
	}
	if len(m.byName) >= maxCap {
		return 0, false
	}

	candidate := m.next
	if candidate == 0 {
		candidate = gvisorDNSPoolFirst
	} else {
		candidate++
	}
	if candidate > gvisorDNSPoolLast || candidate < gvisorDNSPoolFirst {
		candidate = gvisorDNSPoolFirst
	}
	for i := 0; i < len(m.byIP)+1; i++ {
		if _, exists := m.byIP[candidate]; !exists {
			break
		}
		candidate++
		if candidate > gvisorDNSPoolLast || candidate < gvisorDNSPoolFirst {
			candidate = gvisorDNSPoolFirst
		}
	}
	if _, exists := m.byIP[candidate]; exists {
		return 0, false
	}

	m.next = candidate
	entry := &gvisorDNSMapEntry{
		name:    name,
		ip:      candidate,
		expires: now.Add(gvisorDNSMapRetention),
	}
	entry.elem = m.lru.PushFront(entry)
	m.byName[name] = entry
	m.byIP[candidate] = entry
	return candidate, true
}

func (m *gvisorDNSMapper) lookupDomain(ip uint32) (string, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()

	now := m.clock()
	e, ok := m.byIP[ip]
	if !ok {
		return "", false
	}
	if now.After(e.expires) {
		m.deleteEntry(e)
		return "", false
	}
	e.expires = now.Add(gvisorDNSMapRetention)
	m.lru.MoveToFront(e.elem)
	return e.name, true
}

// mapTarget translates a TCP target before the SOCKS5 CONNECT. A normal
// host is returned unchanged with ok=true; a mapped fake IP is returned as
// its domain; an unmapped fake IP returns ("", false) and the caller MUST
// drop the flow.
func (m *gvisorDNSMapper) mapTarget(host string) (string, bool) {
	ip := net.ParseIP(host)
	if ip == nil {
		return host, true
	}
	ip4 := ip.To4()
	if ip4 == nil {
		return host, true
	}
	v := binary.BigEndian.Uint32(ip4)
	if v&gvisorDNSPoolMask != gvisorDNSPoolBase {
		return host, true
	}
	domain, ok := m.lookupDomain(v)
	if !ok {
		return "", false
	}
	return domain, true
}

type gvisorDNSQuestion struct {
	name   string
	qtype  uint16
	qclass uint16
	raw    []byte // exact bytes of the question (offset 12 to qEnd)
}

func gvisorDNSParseQuestion(query []byte) (*gvisorDNSQuestion, bool) {
	if len(query) < 12 {
		return nil, false
	}
	flags := binary.BigEndian.Uint16(query[2:4])
	qr := (flags >> 15) & 1
	opcode := (flags >> 11) & 0x0F
	qdcount := binary.BigEndian.Uint16(query[4:6])
	if qr != 0 || opcode != 0 || qdcount != 1 {
		return nil, false
	}

	p := 12
	var labels []string
	totalWireLen := 0

	for {
		if p >= len(query) {
			return nil, false
		}
		l := int(query[p])
		if l == 0 {
			p++
			totalWireLen++
			break
		}
		if l&0xC0 != 0 || l > 63 {
			return nil, false
		}
		p++
		totalWireLen += 1 + l
		if totalWireLen > 255 {
			return nil, false
		}
		if p+l > len(query) {
			return nil, false
		}
		labelBytes := query[p : p+l]
		var sb strings.Builder
		sb.Grow(l)
		for _, b := range labelBytes {
			if b < 0x21 || b > 0x7E || b == '.' {
				return nil, false
			}
			if b >= 'A' && b <= 'Z' {
				b = b + ('a' - 'A')
			}
			sb.WriteByte(b)
		}
		labels = append(labels, sb.String())
		p += l
	}

	if len(labels) == 0 {
		return nil, false
	}
	if p+4 > len(query) {
		return nil, false
	}
	qtype := binary.BigEndian.Uint16(query[p : p+2])
	qclass := binary.BigEndian.Uint16(query[p+2 : p+4])
	qEnd := p + 4

	return &gvisorDNSQuestion{
		name:   strings.Join(labels, "."),
		qtype:  qtype,
		qclass: qclass,
		raw:    query[12:qEnd],
	}, true
}

// answer parses one DNS query and returns a complete response. ok=false
// means the query is malformed and must be dropped (the client retries).
func (m *gvisorDNSMapper) answer(query []byte) ([]byte, bool) {
	q, ok := gvisorDNSParseQuestion(query)
	if !ok {
		return nil, false
	}

	id := binary.BigEndian.Uint16(query[0:2])
	origFlags := binary.BigEndian.Uint16(query[2:4])
	rd := origFlags & 0x0100

	if q.qtype == 1 && q.qclass == 1 {
		ip, assigned := m.lookupOrAssign(q.name)
		if !assigned {
			// SERVFAIL (RCODE=2), ANCOUNT=0
			flags := uint16(0x8080) | rd | 0x0002
			resp := make([]byte, 12+len(q.raw))
			binary.BigEndian.PutUint16(resp[0:2], id)
			binary.BigEndian.PutUint16(resp[2:4], flags)
			binary.BigEndian.PutUint16(resp[4:6], 1)
			binary.BigEndian.PutUint16(resp[6:8], 0)
			binary.BigEndian.PutUint16(resp[8:10], 0)
			binary.BigEndian.PutUint16(resp[10:12], 0)
			copy(resp[12:], q.raw)
			return resp, true
		}

		// A answer:
		flags := uint16(0x8080) | rd
		resp := make([]byte, 12+len(q.raw)+16)
		binary.BigEndian.PutUint16(resp[0:2], id)
		binary.BigEndian.PutUint16(resp[2:4], flags)
		binary.BigEndian.PutUint16(resp[4:6], 1)
		binary.BigEndian.PutUint16(resp[6:8], 1)
		binary.BigEndian.PutUint16(resp[8:10], 0)
		binary.BigEndian.PutUint16(resp[10:12], 0)
		copy(resp[12:], q.raw)

		ansOff := 12 + len(q.raw)
		resp[ansOff] = 0xC0
		resp[ansOff+1] = 0x0C
		binary.BigEndian.PutUint16(resp[ansOff+2:ansOff+4], 1) // TYPE=A
		binary.BigEndian.PutUint16(resp[ansOff+4:ansOff+6], 1) // CLASS=IN
		binary.BigEndian.PutUint32(resp[ansOff+6:ansOff+10], uint32(gvisorDNSMapTTL/time.Second))
		binary.BigEndian.PutUint16(resp[ansOff+10:ansOff+12], 4)
		binary.BigEndian.PutUint32(resp[ansOff+12:ansOff+16], ip)
		return resp, true
	}

	// For AAAA, HTTPS, PTR, other qtypes or QCLASS!=1: NOERROR / NODATA
	flags := uint16(0x8080) | rd
	resp := make([]byte, 12+len(q.raw))
	binary.BigEndian.PutUint16(resp[0:2], id)
	binary.BigEndian.PutUint16(resp[2:4], flags)
	binary.BigEndian.PutUint16(resp[4:6], 1)
	binary.BigEndian.PutUint16(resp[6:8], 0)
	binary.BigEndian.PutUint16(resp[8:10], 0)
	binary.BigEndian.PutUint16(resp[10:12], 0)
	copy(resp[12:], q.raw)
	return resp, true
}

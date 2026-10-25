// Package tun — gvisor_socks5.go
//
// Portable SOCKS5 client handshake used by the gVisor TUN bridge.
// Supports the no-auth (0x00) and RFC 1929 username/password (0x02) methods.
// This file intentionally has no build tag so it can be unit-tested on any
// platform; the bridge wiring lives in gvisor_bridge.go (linux||android only).
// ==============================================================================
package tun

import (
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"strings"
	"time"
)

// gvisorSocks5HandshakeTimeout — deadline for the entire SOCKS5 handshake.
// The handshake itself is loopback-fast, but the CONNECT reply only comes back
// after the tunnel server dialled the real target, which can take many seconds
// on a DNS tunnel (the engine allows STREAM_SYN 120 s). 10 s aborted healthy
// slow connects and left orphaned half-open streams on the server; 45 s is a
// compromise — still far shorter than the ARQ stream's own lifetimes.
// (Shared with gvisor_bridge.go where the TUN-specific tuning constants live.)
const gvisorSocks5HandshakeTimeout = 45 * time.Second

// gvisorSocksCred holds optional SOCKS5 username/password credentials. When
// user is empty the connection uses the plain no-auth method.
type gvisorSocksCred struct {
	user string
	pass string
}

// gvisorSocksAuthConn transparently upgrades the SOCKS5 greeting to request
// the USER_PASS method and performs the RFC 1929 sub-negotiation before the
// CONNECT request. It intercepts the first write (the greeting), and on the
// first read that returns a USER_PASS method selection it performs the
// sub-negotiation, swallowing the auth reply so the caller's CONNECT flow is
// completely unaware of the extra round-trip.
type gvisorSocksAuthConn struct {
	net.Conn
	cred gvisorSocksCred
	// handshakeState: 0 = greeting not yet written, 1 = greeting sent,
	// waiting for method selection, 2 = handshake complete.
	handshakeState int
}

func (c *gvisorSocksAuthConn) Write(p []byte) (int, error) {
	if c.handshakeState == 0 {
		c.handshakeState = 1
		// The first write of the handshake is the greeting
		// "VER NMETHODS METHODS". Replace it with a USER_PASS-only greeting.
		if len(p) >= 3 && p[0] == 0x05 {
			if _, err := c.Conn.Write([]byte{0x05, 0x01, 0x02}); err != nil {
				return 0, err
			}
			return len(p), nil
		}
	}
	return c.Conn.Write(p)
}

func (c *gvisorSocksAuthConn) Read(p []byte) (int, error) {
	n, err := c.Conn.Read(p)
	if c.handshakeState != 1 || n == 0 {
		return n, err
	}
	if err == nil && n < 2 && len(p) >= 2 {
		var rest int
		rest, err = io.ReadFull(c.Conn, p[n:2])
		n += rest
		if err != nil {
			return 0, err
		}
	}
	// The first response is the method selection "VER METHOD". When the
	// server picks USER_PASS (0x02) send the username/password sub-negotiation
	// and swallow the 2-byte auth reply so the caller's CONNECT flow is
	// completely unaware of the extra round-trip.
	c.handshakeState = 2
	if n >= 2 && p[0] == 0x05 && p[1] == 0x02 {
		user := []byte(c.cred.user)
		pass := []byte(c.cred.pass)
		req := make([]byte, 0, 3+len(user)+len(pass))
		req = append(req, 0x01, byte(len(user)))
		req = append(req, user...)
		req = append(req, byte(len(pass)))
		req = append(req, pass...)
		if _, werr := c.Conn.Write(req); werr != nil {
			return 0, werr
		}
		var authReply [2]byte
		if _, rerr := io.ReadFull(c.Conn, authReply[:]); rerr != nil {
			return 0, rerr
		}
		if authReply[1] != 0x00 {
			return 0, fmt.Errorf("SOCKS5: username/password authentication rejected by proxy")
		}
		// The caller (gvisorSocks5ConnectTCP) expected to read the
		// method-selection reply; present it a synthetic "no-auth chosen"
		// response so the CONNECT handshake proceeds normally.
		p[0], p[1] = 0x05, 0x00
	}
	return n, err
}

func (c *gvisorSocksAuthConn) CloseWrite() error {
	if hc, ok := c.Conn.(interface{ CloseWrite() error }); ok {
		return hc.CloseWrite()
	}
	return c.Conn.Close()
}

// gvisorSocks5ConnectTCP performs SOCKS5 method negotiation (no-auth, or
// USER_PASS when conn is a *gvisorSocksAuthConn) then CONNECT.
func gvisorSocks5ConnectTCP(conn net.Conn, host string, port uint16) error {
	_ = conn.SetDeadline(time.Now().Add(gvisorSocks5HandshakeTimeout))
	defer func() { _ = conn.SetDeadline(time.Time{}) }()

	// Method negotiation — the wrapper (if any) rewrites this greeting.
	if _, err := conn.Write([]byte{0x05, 0x01, 0x00}); err != nil {
		return fmt.Errorf("auth write: %w", err)
	}
	authResp := make([]byte, 2)
	if _, err := io.ReadFull(conn, authResp); err != nil {
		return fmt.Errorf("auth read: %w", err)
	}
	if authResp[0] != 0x05 {
		return fmt.Errorf("SOCKS5: unexpected version in auth response: %d", authResp[0])
	}
	if authResp[1] == 0xff {
		return fmt.Errorf("SOCKS5: proxy rejected all auth methods (no acceptable method)")
	}
	if authResp[1] != 0x00 {
		return fmt.Errorf("SOCKS5: unexpected auth method selected: %d", authResp[1])
	}

	// CONNECT request
	req := gvisorBuildSocks5ConnectRequest(host, port)
	if req == nil {
		return fmt.Errorf("socks5 connect: target domain exceeds 255 bytes")
	}
	if _, err := conn.Write(req); err != nil {
		return fmt.Errorf("connect write: %w", err)
	}

	// Response header: VER REP RSV ATYP
	hdr := make([]byte, 4)
	if _, err := io.ReadFull(conn, hdr); err != nil {
		return fmt.Errorf("connect read: %w", err)
	}
	if hdr[1] != 0x00 {
		return fmt.Errorf("CONNECT refused, code=%d", hdr[1])
	}
	// Drain bound address
	if err := gvisorDrainSocks5Addr(conn, hdr[3]); err != nil {
		return fmt.Errorf("drain addr: %w", err)
	}
	return nil
}

// gvisorBuildSocks5ConnectRequest builds a SOCKS5 CONNECT request for
// host:port.
func gvisorBuildSocks5ConnectRequest(host string, port uint16) []byte {
	if ip := net.ParseIP(host); ip != nil {
		if ip4 := ip.To4(); ip4 != nil {
			req := make([]byte, 10)
			req[0], req[1], req[2], req[3] = 0x05, 0x01, 0x00, 0x01
			copy(req[4:8], ip4)
			binary.BigEndian.PutUint16(req[8:10], port)
			return req
		}
		ip6 := ip.To16()
		req := make([]byte, 22)
		req[0], req[1], req[2], req[3] = 0x05, 0x01, 0x00, 0x04
		copy(req[4:20], ip6)
		binary.BigEndian.PutUint16(req[20:22], port)
		return req
	}
	// Domain name
	h := []byte(host)
	if len(h) > 255 {
		return nil
	}
	req := make([]byte, 7+len(h))
	req[0], req[1], req[2], req[3] = 0x05, 0x01, 0x00, 0x03
	req[4] = byte(len(h))
	copy(req[5:], h)
	binary.BigEndian.PutUint16(req[5+len(h):], port)
	return req
}

// gvisorDrainSocks5Addr reads and discards the bound address from a SOCKS5
// reply.
func gvisorDrainSocks5Addr(conn net.Conn, atyp byte) error {
	var size int
	switch atyp {
	case 0x01:
		size = 4 + 2
	case 0x03:
		lenBuf := make([]byte, 1)
		if _, err := io.ReadFull(conn, lenBuf); err != nil {
			return err
		}
		size = int(lenBuf[0]) + 2
	case 0x04:
		size = 16 + 2
	default:
		return fmt.Errorf("socks5: unknown atyp=%d", atyp)
	}
	_, err := io.ReadFull(conn, make([]byte, size))
	return err
}

// ── Poison-guard target handling (bridge-facing, portable for tests) ────────

// gvisorIsBlockedSOCKSTarget is the bridge's SOCKS target guard (mirrors the
// server rule in internal/udpserver/socks5_upstream.go:validateSOCKSTargetHost,
// read-only). Client-side early refusal: avoids opening ARQ streams the server
// will reject anyway (e.g. Iran poison 10.10.34.35 inside 10/8).
func gvisorIsBlockedSOCKSTarget(host string) bool {
	trimmed := strings.TrimSpace(host)
	if trimmed == "" {
		return true
	}
	lower := strings.ToLower(trimmed)
	if lower == "localhost" || strings.HasSuffix(lower, ".localhost") {
		return true
	}
	ip := net.ParseIP(trimmed)
	if ip == nil {
		return false
	}
	if !ip.IsGlobalUnicast() ||
		ip.IsLoopback() ||
		ip.IsPrivate() ||
		ip.IsMulticast() ||
		ip.IsLinkLocalUnicast() ||
		ip.IsLinkLocalMulticast() ||
		ip.IsUnspecified() {
		return true
	}
	if v4 := ip.To4(); v4 != nil {
		if v4[0] == 100 && v4[1] >= 64 && v4[1] <= 127 {
			return true
		}
		if v4[0] == 198 && (v4[1] == 18 || v4[1] == 19) {
			return true
		}
	}
	return false
}

// gvisorNormalizeTargetHost rewrites a gVisor destination address into the
// host-string form the shared SOCKS target guard expects. IPv4 destinations
// surfaced by the stack can appear as v4-mapped IPv6 literals (::ffff:a.b.c.d)
// because the client route table also advertises an IPv6 default route; the
// guard and the CONNECT builder both want the plain IPv4 form, so collapse it.
func gvisorNormalizeTargetHost(host string) string {
	ip := net.ParseIP(host)
	if ip == nil {
		return host
	}
	if v4 := ip.To4(); v4 != nil {
		return v4.String()
	}
	return host
}

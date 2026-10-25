//go:build linux || android

// Package tun — gvisor_bridge.go
//
// Implements the TUN bridge using the gVisor netstack. Reads raw IP packets
// from a TUN file descriptor (provided by
// Android's VpnService.establish()), sets up a virtual TCP/IP stack, and
// forwards every TCP flow through the loopback SOCKS5 proxy; DNS (UDP/53) is
// answered in-process locally using fake IPs, and every other UDP
// destination is refused so the stack sends ICMP Port Unreachable (apps then
// fall back to TCP).
// ==============================================================================
package tun

import (
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"strconv"
	"sync"
	"sync/atomic"
	"time"

	"gvisor.dev/gvisor/pkg/tcpip"
	"gvisor.dev/gvisor/pkg/tcpip/adapters/gonet"
	"gvisor.dev/gvisor/pkg/tcpip/header"
	"gvisor.dev/gvisor/pkg/tcpip/link/fdbased"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv4"
	"gvisor.dev/gvisor/pkg/tcpip/network/ipv6"
	"gvisor.dev/gvisor/pkg/tcpip/stack"
	"gvisor.dev/gvisor/pkg/tcpip/transport/tcp"
	"gvisor.dev/gvisor/pkg/tcpip/transport/udp"
	"gvisor.dev/gvisor/pkg/waiter"
)

const gvisorTunNICID tcpip.NICID = 1

// gvisorDNSPort — the only UDP destination the bridge serves; DNS is handled
// in-process locally (see gvisorProxyDNSDirect). DoT/TCP-53 get RST, all other UDP
// gets ICMP Port Unreachable.
const gvisorDNSPort uint16 = 53

// ── Tuning constants ───────────────────────────────────────────────────────────

const (
	// gvisorRelayBufSize — each recycled relay buffer. Sized to 64 KB to match
	// high-throughput SOCKS relay buffers in hev-socks5-tunnel and modern netstack.
	gvisorRelayBufSize = 64 * 1024

	// gvisorTCPMaxInFlight — maximum concurrent TCP connections being
	// proxied. Allows up to 256 concurrent connections, providing solid headroom
	// for media apps while strictly bounding memory to ~80 MB total, avoiding Android LMK.
	gvisorTCPMaxInFlight = 256

	// gvisorTCPDialTimeout — timeout for dialling the local SOCKS5 proxy
	// (loopback, sub-millisecond when the engine is healthy).
	gvisorTCPDialTimeout = 10 * time.Second

	// gvisorRelayWriteStallTimeout — max time a single relay Write may block
	// without progress before the whole flow is torn down. Without this a
	// wedged peer leaves goroutines (and orphaned tunnel streams
	// retransmitting to the server) alive until the profile is stopped.
	gvisorRelayWriteStallTimeout = 90 * time.Second

	// gvisorRelayIdleTimeout — close a flow after this much total silence in
	// BOTH directions. 10 minutes protects quiet background WebSockets,
	// Telegram/Signal, and push connections from premature termination.
	gvisorRelayIdleTimeout = 10 * time.Minute

	// gvisorRelayIdleCheckInterval — how often the per-flow idle watchdog
	// ticks.
	gvisorRelayIdleCheckInterval = 15 * time.Second

	// gvisorUDPReadTimeout is the per-iteration deadline on the UDP relay
	// socket (allows ctx.Done detection without blocking forever).
	gvisorUDPReadTimeout = 5 * time.Second

	// gvisorSocks5HandshakeTimeout lives in gvisor_socks5.go (portable,
	// shared with the unit tests that exercise the handshake on any
	// platform).
)

// ── Buffer pool ────────────────────────────────────────────────────────────────

var gvisorRelayBufPool = sync.Pool{
	New: func() any {
		b := make([]byte, gvisorRelayBufSize)
		return &b
	},
}

func gvisorGetRelayBuf() []byte  { return *(gvisorRelayBufPool.Get().(*[]byte)) }
func gvisorPutRelayBuf(b []byte) { gvisorRelayBufPool.Put(&b) }

// Rate-limit slip for the per-packet UDP refusal log line: per-packet
// logging is FORBIDDEN, so repeated refusals are announced at most once
// per interval (atomic timestamp, CAS-guarded).
var gvisorRefusalLogLast atomic.Int64

// Total DNS queries answered locally across all UDP endpoints (shared
// so port-randomized queries don't reset the rate-limit ramp).
var gvisorDNSAnswered atomic.Uint64

// ── Stack setup (the synchronous portion of bridge startup) ───────────────────

// gvisorSetupStack performs every fallible, synchronous part of bridge
// startup: stack + NIC + routes + forwarders + transport handler
// registrations. It returns the live stack so the caller can spawn only the
// goroutine that awaits ctx and closes it on shutdown.
func gvisorSetupStack(ctx context.Context, tunFd int, mtu int, socksAddr string, mapper *gvisorDNSMapper) (*stack.Stack, error) {
	gvisorDNSAnswered.Store(0)
	gvisorBridgeLog("starting bridge: tunFd=%d mtu=%d socksAddr=%s", tunFd, mtu, socksAddr)

	s := stack.New(stack.Options{
		NetworkProtocols: []stack.NetworkProtocolFactory{
			ipv4.NewProtocol,
			ipv6.NewProtocol,
		},
		TransportProtocols: []stack.TransportProtocolFactory{
			tcp.NewProtocol,
			udp.NewProtocol,
		},
	})

	// Disable Nagle's algorithm — flush writes immediately for low-latency
	// streaming and interactive apps instead of waiting to coalesce segments.
	delay := tcpip.TCPDelayEnabled(false)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &delay)

	// Enable Selective Acknowledgement (SACK) for faster loss recovery on
	// lossy mobile connections.
	sack := tcpip.TCPSACKEnabled(true)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sack)

	// Disable receive buffer auto-tuning to avoid aggressive expansion on
	// 0ms loopback which causes bufferbloat and memory bloat on mobile.
	moderate := tcpip.TCPModerateReceiveBufferOption(false)
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &moderate)

	// Sized for high-throughput streaming over the local TUN loopback: 64 KB default
	// keeps baseline heap low across many connections, while 256 KB ceiling
	// strictly bounds memory under concurrency (protecting against Android LMK).
	sendBuf := tcpip.TCPSendBufferSizeRangeOption{Min: 4096, Default: 64 << 10, Max: 256 << 10}
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &sendBuf)

	// Matching receive buffer range.
	recvBuf := tcpip.TCPReceiveBufferSizeRangeOption{Min: 4096, Default: 64 << 10, Max: 256 << 10}
	s.SetTransportProtocolOption(tcp.ProtocolNumber, &recvBuf)

	ep, err := fdbased.New(&fdbased.Options{
		FDs:            []int{tunFd},
		MTU:            uint32(mtu),
		EthernetHeader: false, // TUN (raw IP), not TAP
		// GSOMaxSize, gVisor GSO and GRO are intentionally NOT set.
		// VpnService on Android does NOT support IFF_VNET_HDR, so gVisor GSO
		// superframes are silently dropped by the kernel — causing near-zero
		// throughput. RXChecksumOffload is safe because gVisor validates
		// checksums internally before passing to the stack regardless.
		RXChecksumOffload: true,
	})
	if err != nil {
		return nil, fmt.Errorf("gvisor bridge: fdbased.New: %w", err)
	}

	if tcpErr := s.CreateNIC(gvisorTunNICID, ep); tcpErr != nil {
		return nil, fmt.Errorf("gvisor bridge: CreateNIC: %v", tcpErr)
	}

	// Promiscuous + spoofing: accept packets for any destination IP.
	if tcpErr := s.SetPromiscuousMode(gvisorTunNICID, true); tcpErr != nil {
		return nil, fmt.Errorf("gvisor bridge: SetPromiscuousMode: %v", tcpErr)
	}
	if tcpErr := s.SetSpoofing(gvisorTunNICID, true); tcpErr != nil {
		return nil, fmt.Errorf("gvisor bridge: SetSpoofing: %v", tcpErr)
	}

	s.SetRouteTable([]tcpip.Route{
		{Destination: header.IPv4EmptySubnet, NIC: gvisorTunNICID},
		{Destination: header.IPv6EmptySubnet, NIC: gvisorTunNICID},
	})

	gvisorBridgeLog("gVisor stack created, setting up forwarders")

	// ── TCP forwarder ──────────────────────────────────────────────────────────
	// No connection pool: each TCP flow dials the loopback SOCKS5 proxy
	// directly. Loopback dials are sub-millisecond so pooling adds complexity
	// (race conditions, stale connections) without measurable gain.
	sem := make(chan struct{}, gvisorTCPMaxInFlight)

	tcpFwd := tcp.NewForwarder(s, 0, gvisorTCPMaxInFlight, func(r *tcp.ForwarderRequest) {
		id := r.ID()
		dstAddr := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))

		// DNS-over-TCP (53) and DoT (853) cannot be served by this bridge:
		// the client resolves DNS in-process over UDP only, so a CONNECT for
		// the virtual DNS IP (or any :53/:853 TCP flow) would travel to the
		// tunnel server, time out there, and stall app startup for seconds.
		// RST immediately so clients fall back to UDP/53 (handled in-process).
		if id.LocalPort == gvisorDNSPort || id.LocalPort == 853 {
			r.Complete(true)
			return
		}

		// IPv6 blocking (default ON): RST IPv6 destinations on the spot.
		// Apps with cached AAAA answers (e.g. YouTube/Cronet) otherwise send
		// every IPv6 CONNECT through the SOCKS/tunnel and only learn of the
		// failure after a full tunnel round-trip with "connect refused code
		// 3". An immediate RST makes them fall back to the IPv4 address
		// right away.
		if id.LocalAddress.Len() == 16 && gvisorIPv6Blocked() {
			r.Complete(true)
			return
		}

		// Backpressure: fail fast at SYN time without completing the handshake.
		select {
		case sem <- struct{}{}:
		default:
			gvisorBridgeWarn("TCP backpressure: dropping %s (>%d concurrent)", dstAddr, gvisorTCPMaxInFlight)
			r.Complete(true) // send RST at SYN time
			return
		}

		var wq waiter.Queue
		ep, tcpErr := r.CreateEndpoint(&wq)
		if tcpErr != nil {
			<-sem
			gvisorBridgeErr("TCP CreateEndpoint failed for %s: %v", dstAddr, tcpErr)
			r.Complete(true) // send RST
			return
		}
		r.Complete(false)
		conn := gonet.NewTCPConn(&wq, ep)

		go func() {
			defer func() { <-sem }()
			defer conn.Close()
			if ctx.Err() != nil {
				return
			}
			gvisorProxyTCPDirect(ctx, conn, dstAddr, socksAddr, mapper)
		}()
	})
	s.SetTransportProtocolHandler(tcp.ProtocolNumber, tcpFwd.HandlePacket)

	// ── UDP: port-53 forwarder wrapped in a refusal gate ───────────────────────
	// DNS (port 53) is served in-process (no SOCKS5 overhead). At the
	// pinned upstream module udp.NewForwarder has NO
	// bool-returning handler and (*Forwarder).HandlePacket always returns
	// true — so port≠53 refusals happen HERE, in the per-stack transport
	// handler: returning false marks the packet unhandled and the network
	// layer answers with ICMP Port Unreachable, making browsers and QUIC
	// clients fall back to TCP immediately instead of stalling.
	udpFwd := udp.NewForwarder(s, func(r *udp.ForwarderRequest) {
		id := r.ID()
		if id.LocalPort != gvisorDNSPort {
			// Never reached via the gate below; kept as a guard so the
			// forwarder alone never widens its surface.
			return
		}

		dstAddr := net.JoinHostPort(id.LocalAddress.String(), strconv.Itoa(int(id.LocalPort)))

		var wq waiter.Queue
		ep, udpErr := r.CreateEndpoint(&wq)
		if udpErr != nil {
			gvisorBridgeErr("UDP CreateEndpoint failed for %s: %v", dstAddr, udpErr)
			return
		}

		conn := gonet.NewUDPConn(&wq, ep)
		go func() {
			defer conn.Close()
			if ctx.Err() != nil {
				return
			}
			gvisorProxyDNSDirect(ctx, conn, dstAddr, mapper)
		}()
	})
	s.SetTransportProtocolHandler(header.UDPProtocolNumber, func(id stack.TransportEndpointID, pkt *stack.PacketBuffer) bool {
		if id.LocalPort == gvisorDNSPort {
			return udpFwd.HandlePacket(id, pkt)
		}
		// Refuse → the stack falls through to the unknown-destination path
		// and emits ICMP Port Unreachable for this packet.
		// Rate-limited WARN — per-packet logging is forbidden.
		now := time.Now().UnixNano()
		if last := gvisorRefusalLogLast.Load(); now-last > int64(10*time.Second) {
			if gvisorRefusalLogLast.CompareAndSwap(last, now) {
				gvisorBridgeWarn("refused non-DNS UDP packet to port %d (ICMP Port Unreachable)", id.LocalPort)
			}
		}
		return false
	})

	gvisorBridgeLog("forwarders ready, bridge is active")
	return s, nil
}

// ── TCP proxy (direct dial per flow) ───────────────────────────────────────────

// gvisorProxyTCPDirect dials the SOCKS5 proxy, performs CONNECT handshake,
// then relays bidirectionally. Each flow gets a fresh connection — loopback
// is fast.
func gvisorProxyTCPDirect(ctx context.Context, src net.Conn, dstAddr string, socksAddr string, mapper *gvisorDNSMapper) {
	origHost, portStr, err := net.SplitHostPort(dstAddr)
	if err != nil {
		gvisorBridgeErr("TCP parse %s: %v", dstAddr, err)
		return
	}
	port, err := gvisorParsePort(portStr)
	if err != nil {
		gvisorBridgeErr("TCP port %s: %v", dstAddr, err)
		return
	}

	host, ok := mapper.mapTarget(origHost)
	if !ok {
		// A fake IP with no live mapping is never a real target. This
		// happens after a reconnect while Android still caches an old
		// answer: fail closed so the app re-resolves. NEVER dial 240/4
		// and NEVER pass the fake IP to the SOCKS CONNECT.
		gvisorBridgeWarn("dropping TCP flow to unmapped fake IP %s", dstAddr)
		return
	}
	host = gvisorNormalizeTargetHost(host)
	if gvisorIsBlockedSOCKSTarget(host) {
		gvisorBridgeWarn("refused blocked target %s", dstAddr)
		return
	}

	dialCtx, dialCancel := context.WithTimeout(ctx, gvisorTCPDialTimeout)
	defer dialCancel()
	// Use plain dial for loopback — VpnService.protect() is NOT needed for
	// 127.0.0.1 (loopback traffic never enters the TUN interface) and calling
	// protect on loopback sockets can cause routing issues on some devices.
	d := net.Dialer{}
	proxy, err := d.DialContext(dialCtx, "tcp", socksAddr)
	if err != nil {
		gvisorBridgeErr("TCP dial SOCKS5 for %s: %v", dstAddr, err)
		return
	}
	defer proxy.Close()

	// Apply the local proxy's credentials (no-op when auth is disabled).
	proxy = gvisorSocksCredsWrapper(proxy)

	if err := gvisorSocks5ConnectTCP(proxy, host, port); err != nil {
		gvisorBridgeErr("TCP SOCKS5 CONNECT %s: %v", dstAddr, err)
		return
	}

	if host != origHost {
		gvisorBridgeLog("TCP connected: %s:%d (via %s)", host, port, dstAddr)
	} else {
		gvisorBridgeLog("TCP connected: %s", dstAddr)
	}
	gvisorBidirectionalRelay(ctx, src, proxy)
}

// ── DNS proxy (in-process fake DNS) ──────────────────────────────────────────

// gvisorProxyDNSDirect reads DNS queries from the gVisor UDP endpoint and
// answers them locally from the fake-IP mapper (see gvisor_dns.go).
//
// A query gets an A answer in 240.0.0.0/4; AAAA and every other qtype get a
// NOERROR/NODATA answer. When the app later connects to that fake IP, the TCP
// forwarder maps it back to the domain and the exit node resolves the domain
// (internal/exit/exit.go, dialWithDNSCache). Zero DNS round trips.
//
// A malformed query is dropped (the stub resolver retries); a well-formed
// query always gets an answer, including SERVFAIL when the mapper is full.
func gvisorProxyDNSDirect(ctx context.Context, src net.Conn, dstAddr string, mapper *gvisorDNSMapper) {
	buf := make([]byte, 4096)
	idleTimeouts := 0
	for {
		if ctx.Err() != nil {
			return
		}
		// Deadline so the loop notices context cancellation periodically.
		_ = src.SetReadDeadline(time.Now().Add(gvisorUDPReadTimeout))

		n, readErr := src.Read(buf)
		if readErr != nil {
			if netErr, ok := readErr.(net.Error); ok && netErr.Timeout() {
				idleTimeouts++
				if idleTimeouts >= 3 { // 3x5s = 15s idle - exit goroutine
					return
				}
				continue
			}
			return
		}
		idleTimeouts = 0
		if n == 0 {
			continue
		}

		resp, ok := mapper.answer(buf[:n])
		if !ok {
			gvisorBridgeWarn("DNS query dropped (malformed) from %s", dstAddr)
			continue
		}
		if _, werr := src.Write(resp); werr != nil {
			return
		}
		// The in-app log is a 2000-line ring buffer: a per-query line would
		// drown the useful bridge lines. Log the first 20 answers, then
		// every 100th.
		answered := gvisorDNSAnswered.Add(1)
		if answered <= 20 || answered%100 == 0 {
			gvisorBridgeLog("DNS answered locally %s (%d bytes, %d answered)", dstAddr, len(resp), answered)
		}
	}
}

// ── Relay ──────────────────────────────────────────────────────────────────────

// gvisorHalfCloser is implemented by connections that support TCP
// half-close. Both *net.TCPConn and *gonet.TCPConn implement this interface.
type gvisorHalfCloser interface {
	CloseWrite() error
}

// gvisorBidirectionalRelay copies data between a (TUN/gVisor side) and
// b (SOCKS5 proxy side) until the flow finishes or is aborted.
//
//	a -> b  =  upload   (device -> internet)
//	b -> a  =  download (internet -> device)
//
// Uses pooled 16 KB buffers to bound per-flow memory.
//
// Abort semantics:
//   - ctx cancelled (bridge bounce / VPN stop) → both sides closed at once.
//   - Write stalled longer than gvisorRelayWriteStallTimeout → both sides
//     closed.
//   - non-EOF read error (RST / reset / closed) → BOTH sides closed, so an
//     app-side abort propagates to the tunnel instead of half-closing and
//     waiting for the server to close its half forever.
//   - clean EOF → graceful half-close of the peer's write side only, keeping
//     correct HTTP/1.1 request-response completion.
//   - total silence in both directions for gvisorRelayIdleTimeout → both
//     closed (watchdog for flows that stall without producing any error).
func gvisorBidirectionalRelay(ctx context.Context, a, b net.Conn) {
	var lastActivity atomic.Int64
	lastActivity.Store(time.Now().UnixNano())

	closeBoth := func() {
		_ = a.Close()
		_ = b.Close()
	}

	// Bridge teardown (bounce/stop) must tear down every live relay so no
	// orphaned SOCKS streams survive the bridge.
	stopWatch := context.AfterFunc(ctx, closeBoth)
	defer stopWatch()

	done := make(chan struct{})
	defer close(done)
	go func() {
		ticker := time.NewTicker(gvisorRelayIdleCheckInterval)
		defer ticker.Stop()
		for {
			select {
			case <-done:
				return
			case <-ticker.C:
				if time.Since(time.Unix(0, lastActivity.Load())) > gvisorRelayIdleTimeout {
					closeBoth()
					return
				}
			}
		}
	}()

	var wg sync.WaitGroup
	wg.Add(2)
	copyHalf := func(dst, src net.Conn) {
		defer wg.Done()
		buf := gvisorGetRelayBuf()
		defer gvisorPutRelayBuf(buf)

		var lastDeadlineSet time.Time
		for {
			n, readErr := src.Read(buf)
			if n > 0 {
				if time.Since(lastDeadlineSet) > 5*time.Second {
					_ = dst.SetWriteDeadline(time.Now().Add(gvisorRelayWriteStallTimeout))
					lastDeadlineSet = time.Now()
				}
				_, writeErr := dst.Write(buf[:n])
				lastActivity.Store(time.Now().UnixNano())
				if writeErr != nil {
					// Stalled write or peer reset: abort the whole flow.
					closeBoth()
					return
				}
			}
			if readErr != nil {
				if errors.Is(readErr, io.EOF) {
					// Clean half-close: this direction finished normally.
					if hc, ok := dst.(gvisorHalfCloser); ok {
						_ = hc.CloseWrite()
					} else {
						_ = dst.Close()
					}
					return
				}
				// Reset/timeout/closed: tear down BOTH directions now —
				// only stopping this half would leave the SOCKS side (and
				// the server's stream) alive forever.
				closeBoth()
				return
			}
		}
	}

	go copyHalf(b, a) // upload:   TUN -> proxy
	go copyHalf(a, b) // download: proxy -> TUN
	wg.Wait()
}

// ── Utilities ──────────────────────────────────────────────────────────────────

func gvisorParsePort(portStr string) (uint16, error) {
	p, err := strconv.ParseUint(portStr, 10, 16)
	if err != nil {
		return 0, fmt.Errorf("gvisor bridge: invalid port %q: %w", portStr, err)
	}
	return uint16(p), nil
}

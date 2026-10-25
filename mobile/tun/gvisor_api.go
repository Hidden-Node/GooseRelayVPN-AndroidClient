//go:build linux || android

// Package tun — gvisor_api.go
//
// Exported gomobile-compatible lifecycle for the gVisor TUN bridge and the
// SOCKS credential plumbing.
//
// Lifecycle shape: the SYNCHRONOUS portion of startup (stack creation, NIC,
// SetRouteTable, forwarder registration, transport handler registration, log
// open) runs inline and can fail with a real error; on success StartTunGvisor
// spawns only the shutdown-await goroutine and returns nil AT ONCE — nothing
// may wait for bridge exit before returning (gomobile expects a prompt
// call). Shutdown waits belong to StopTunGvisor only.
// ==============================================================================
package tun

import (
	"context"
	"errors"
	"fmt"
	"net"
	"sync"
	"syscall"
	"time"
)

var (
	gvisorMu     sync.Mutex
	gvisorCancel context.CancelFunc
	gvisorDone   chan struct{}

	// Credentials of the local SOCKS5 proxy (from the profile config),
	// captured by StartTunGvisor and used by the bridge's internal SOCKS5
	// dialer when the proxy requires username/password authentication.
	// Guarded by gvisorMu.
	gvisorSocksUser string
	gvisorSocksPass string
)

// gvisorStartHook is nil in production; the linux lifecycle test uses it
// to hold a start inside the startup window.
var gvisorStartHook func()

// gvisorDupFd duplicates a file descriptor so the gVisor bridge and
// Android each own independent copies. Returns ok=false when Dup fails;
// the caller MUST abort startup in that case rather than proceed with the
// borrowed fd (double-close on stop).
func gvisorDupFd(fd int) (int, bool) {
	dup, err := syscall.Dup(fd)
	if err != nil {
		return -1, false
	}
	return dup, true
}

// gvisorCurrentSocksCred returns the credentials of the local SOCKS5 proxy
// the bridge dials. They were captured by StartTunGvisor.
func gvisorCurrentSocksCred() gvisorSocksCred {
	gvisorMu.Lock()
	defer gvisorMu.Unlock()
	if gvisorSocksUser == "" {
		return gvisorSocksCred{}
	}
	return gvisorSocksCred{user: gvisorSocksUser, pass: gvisorSocksPass}
}

// gvisorSocksCredsWrapper returns a connection wrapper that injects the
// configured credentials into the SOCKS5 handshake (method 0x02 USER_PASS)
// when authentication is enabled on the local proxy. The returned conn must
// be used for the handshake BEFORE any payload bytes flow. The handshake
// itself lives in gvisor_socks5.go (portable, unit-tested).
func gvisorSocksCredsWrapper(conn net.Conn) net.Conn {
	cred := gvisorCurrentSocksCred()
	if cred == (gvisorSocksCred{}) {
		return conn
	}
	return &gvisorSocksAuthConn{Conn: conn, cred: cred}
}

// StartTunGvisor starts the gVisor TUN bridge.
//
// It must be called when the local SOCKS5 proxy is already listening on
// socksAddr.
//
// Parameters:
//
//	tunFd     – raw file descriptor from Android's
//	            ParcelFileDescriptor.getFd() (VpnService.establish()).
//	mtu       – MTU of the TUN interface (<= 0 defaults to 1500).
//	socksAddr – "host:port" of the SOCKS5 proxy to dial per flow
//	            (e.g. "127.0.0.1:1080"); must not be empty.
//	socksUser/socksPass – credentials of that local proxy, threaded from
//	            the profile config ("" = plain no-auth).
//	logPath   – the same Go-core log file path the service passes to
//	            StartClient; bridge lines are appended there so they reach
//	            the in-app Logs tab via the existing file tailer (empty
//	            disables the file sink).
func StartTunGvisor(tunFd int64, mtu int64, socksAddr string, socksUser string, socksPass string, logPath string) error {
	gvisorMu.Lock()
	if gvisorCancel != nil {
		gvisorMu.Unlock()
		return errors.New("gvisor tun bridge already running")
	}
	if socksAddr == "" {
		gvisorMu.Unlock()
		return errors.New("socksAddr must not be empty")
	}
	if tunFd < 0 {
		gvisorMu.Unlock()
		return fmt.Errorf("invalid tunFd: %d", tunFd)
	}

	gvisorSocksUser = socksUser
	gvisorSocksPass = socksPass

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	gvisorCancel = cancel
	gvisorDone = done
	gvisorMu.Unlock()

	if gvisorStartHook != nil {
		gvisorStartHook()
	}

	if err := gvisorLogOpen(logPath); err != nil {
		cancel()
		gvisorMu.Lock()
		gvisorCancel = nil
		gvisorDone = nil
		gvisorMu.Unlock()
		close(done)
		return fmt.Errorf("gvisor bridge: %w", err)
	}

	safeFd, ok := gvisorDupFd(int(tunFd))
	if !ok {
		cancel()
		gvisorMu.Lock()
		gvisorCancel = nil
		gvisorDone = nil
		gvisorMu.Unlock()
		close(done)
		gvisorLogClose()
		return fmt.Errorf("gvisor bridge: tun fd duplication failed (fd exhaustion); refusing to start")
	}

	tunMTU := int(mtu)
	if tunMTU <= 0 {
		tunMTU = 1500
	}

	// Announce auth use once at startup.
	if cred := gvisorCurrentSocksCred(); cred != (gvisorSocksCred{}) {
		gvisorBridgeLog("gVisor bridge using SOCKS5 auth on %s", socksAddr)
	}

	mapper := newGvisorDNSMapper()

	// Synchronous setup — every failure mode here returns a real error and
	// leaves no half-started state behind.
	s, err := gvisorSetupStack(ctx, safeFd, tunMTU, socksAddr, mapper)
	if err != nil {
		_ = syscall.Close(safeFd)
		gvisorLogClose()
		cancel()
		gvisorMu.Lock()
		gvisorCancel = nil
		gvisorDone = nil
		gvisorMu.Unlock()
		close(done)
		return err
	}

	// Prompt return: only the shutdown-await goroutine outlives this call,
	// plus whatever per-request goroutines the forwarders spawn on demand.
	go func() {
		<-ctx.Done()
		gvisorBridgeLog("bridge shutting down")
		s.Close()
		_ = syscall.Close(safeFd)

		// Clear lifecycle state before signalling completion.
		gvisorMu.Lock()
		gvisorCancel = nil
		gvisorDone = nil
		gvisorMu.Unlock()

		gvisorLogClose()
		close(done)
	}()
	return nil
}

// StopTunGvisor stops the gVisor TUN bridge and waits for it to exit.
// Idempotent — safe to call multiple times, and a no-op before any start.
func StopTunGvisor() {
	gvisorMu.Lock()
	cancel := gvisorCancel
	done := gvisorDone
	// Do NOT clear gvisorCancel/gvisorDone here — the lifecycle goroutine
	// owns that cleanup.
	gvisorMu.Unlock()

	if cancel != nil {
		cancel()
	}
	if done != nil {
		select {
		case <-done:
		case <-time.After(2 * time.Second):
			gvisorBridgeErr("gVisor bridge did not stop within 2s; continuing teardown")
		}
	}
	// Belt-and-braces flush; normally already closed by the lifecycle
	// goroutine (mutex-guarded, so ordering is safe either way).
	gvisorLogClose()
}

// IsTunGvisorRunning returns true if the bridge is currently active.
func IsTunGvisorRunning() bool {
	gvisorMu.Lock()
	defer gvisorMu.Unlock()
	return gvisorCancel != nil
}

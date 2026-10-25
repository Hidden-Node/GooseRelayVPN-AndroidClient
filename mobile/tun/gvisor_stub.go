//go:build !linux && !android

// Package tun — gvisor_stub.go
//
// Stub surface for platforms where the gVisor TUN bridge is unavailable
// (fdbased is linux-only). Keeps `go build ./mobile/tun/...` and `go vet`
// green on Windows/macOS/CI-non-Android hosts. The portable files
// (gvisor_state.go, gvisor_log.go, gvisor_dns.go, gvisor_socks5.go) need no stubs.
// ==============================================================================
package tun

import (
	"errors"
)

var errGvisorTunNotSupported = errors.New("gvisor tun bridge is only supported on Android/Linux")

// StartTunGvisor is a stub on non-Android/Linux platforms.
func StartTunGvisor(_ int64, _ int64, _ string, _ string, _ string, _ string) error {
	return errGvisorTunNotSupported
}

// StopTunGvisor is a no-op on non-Android/Linux platforms.
func StopTunGvisor() {}

// IsTunGvisorRunning always returns false on non-Android/Linux platforms.
func IsTunGvisorRunning() bool { return false }

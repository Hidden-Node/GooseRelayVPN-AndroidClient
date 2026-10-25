//go:build linux || android

// Package tun — gvisor_bridge_linux_test.go
//
// Linux-only lifecycle accounting for the gVisor bridge. Needs
// /proc/self/fd and real dup semantics, so it is gated to linux and
// executes on CI's ubuntu runner (GOOS=linux vet type-checks it on
// other hosts).
// ==============================================================================
package tun

import (
	"os"
	"strconv"
	"sync"
	"testing"
	"time"
)

// fdsResolvingTo counts open fds whose /proc/self/fd target equals target.
// Entries whose readlink fails are skipped (the fd can close between the
// directory read and the readlink).
func fdsResolvingTo(target string) int {
	entries, err := os.ReadDir("/proc/self/fd")
	if err != nil {
		return -1
	}
	n := 0
	for _, e := range entries {
		got, err := os.Readlink("/proc/self/fd/" + e.Name())
		if err != nil {
			continue
		}
		if got == target {
			n++
		}
	}
	return n
}

// TestGvisorStackFdAccounting starts/stops the bridge against /dev/null
// (a valid fd that stack setup accepts) and asserts the bridge's dup of
// that fd is closed on the shutdown path: the number of fds resolving to
// the same target is baseline+1 after start and back to baseline after
// stop.
//
// It deliberately does NOT assert total process fd equality: gVisor's
// fdbased readv dispatcher allocates one eventfd per start
// (pkg/tcpip/link/stopfd), and the pinned gVisor version never closes it
// (StopFD has no Close; readVDispatcher.release does not touch it). That
// fd is unexported and unreachable from this package; the pin is
// immutable from the Android side. Counting the TUN target isolates the
// property this test owns.
//
// /dev/null proves fd ACCOUNTING only, not traffic — traffic validation
// belongs to plan 073's device check. The setup-error close is one
// syscall.Close line on each exclusive path (review-verified); forcing
// fdbased.New to fail deterministically is unreliable, so it is
// intentionally NOT covered here.
func TestGvisorStackFdAccounting(t *testing.T) {
	f, err := os.OpenFile("/dev/null", os.O_RDWR, 0)
	if err != nil {
		t.Fatalf("open /dev/null: %v", err)
	}
	defer func() { _ = f.Close() }()

	target, err := os.Readlink("/proc/self/fd/" + strconv.Itoa(int(f.Fd())))
	if err != nil {
		t.Fatalf("readlink tun fd: %v", err)
	}

	before := fdsResolvingTo(target)
	if before < 1 {
		t.Fatalf("baseline fds resolving to %q = %d, want >= 1", target, before)
	}

	if err := StartTunGvisor(int64(f.Fd()), 1500, "127.0.0.1:1", "", "", ""); err != nil {
		t.Fatalf("StartTunGvisor against /dev/null: %v", err)
	}

	during := fdsResolvingTo(target)
	if during != before+1 {
		StopTunGvisor()
		t.Fatalf("dup'd TUN fd not visible during start: fds resolving to %q before=%d during=%d, want during=before+1", target, before, during)
	}

	StopTunGvisor()

	after := fdsResolvingTo(target)
	if after != before {
		t.Fatalf("dup'd TUN fd leaked across start/stop: fds resolving to %q before=%d after=%d", target, before, after)
	}
}

func TestGvisorStopAfterFailedStart(t *testing.T) {
	anchor := t.TempDir() + "/anchor"
	if err := os.WriteFile(anchor, []byte("x"), 0644); err != nil {
		t.Fatalf("anchor write: %v", err)
	}
	badLogPath := anchor + "/bridge.log"

	// Pre-spawned stoppers: the publish→unwind window is ~2 mutex
	// sections + one failing syscall — far narrower than goroutine-spawn
	// latency, so a stopper spawned AFTER the failing start can never
	// observe it (reviewer-proved: 200 sequential rounds pass on
	// unfixed code in 0.00 s). A stopper already blocked in Stop BEFORE
	// the failing start hits the window on round 0.
	for i := 0; i < 2000; i++ {
		stopped := make(chan struct{})
		go func() {
			StopTunGvisor()
			close(stopped)
		}()
		if err := StartTunGvisor(3, 1500, "127.0.0.1:1", "", "", badLogPath); err == nil {
			t.Fatalf("round %d: bad-log start must fail", i)
		}
		select {
		case <-stopped:
		case <-time.After(2 * time.Second):
			t.Fatalf("round %d: stop blocked after failed start", i)
		}
	}

	if err := StartTunGvisor(3, 1500, "127.0.0.1:1", "", "", ""); err != nil {
		t.Fatalf("bridge must be restartable after a failed start: %v", err)
	}
	StopTunGvisor()
}

func TestGvisorStopDuringStartup(t *testing.T) {
	entered := make(chan struct{})
	release := make(chan struct{})
	var releaseOnce sync.Once
	releaseStart := func() { releaseOnce.Do(func() { close(release) }) }

	gvisorStartHook = func() {
		close(entered)
		<-release
	}

	startErr := make(chan error, 1)
	startDone := make(chan struct{})
	go func() {
		startErr <- StartTunGvisor(3, 1500, "127.0.0.1:1", "", "", "")
		close(startDone)
	}()

	t.Cleanup(func() {
		releaseStart()
		select {
		case <-startDone:
		case <-time.After(5 * time.Second):
		}
		gvisorStartHook = nil
		stopDone := make(chan struct{})
		go func() {
			StopTunGvisor()
			close(stopDone)
		}()
		select {
		case <-stopDone:
		case <-time.After(5 * time.Second):
		}
	})

	select {
	case <-entered:
	case <-time.After(5 * time.Second):
		t.Fatal("startup hook did not run")
	}

	if !IsTunGvisorRunning() {
		t.Fatal("lifecycle is not published before the fallible setup: the running guard and a racing stop are blind")
	}

	stopped := make(chan struct{})
	go func() {
		StopTunGvisor()
		close(stopped)
	}()

	select {
	case <-stopped:
		t.Fatal("StopTunGvisor returned while startup was still in progress")
	default:
	}

	releaseStart()

	var err error
	select {
	case err = <-startErr:
	case <-time.After(5 * time.Second):
		t.Fatal("StartTunGvisor did not return after release")
	}
	if err != nil {
		t.Fatalf("StartTunGvisor: %v", err)
	}

	select {
	case <-stopped:
	case <-time.After(5 * time.Second):
		t.Fatal("StopTunGvisor did not complete after startup finished")
	}

	if IsTunGvisorRunning() {
		t.Fatal("bridge still running after stop")
	}
}

func TestGvisorTuningConstants(t *testing.T) {
	if gvisorTCPMaxInFlight < 512 {
		t.Errorf("gvisorTCPMaxInFlight = %d, want >= 512", gvisorTCPMaxInFlight)
	}
	if gvisorRelayIdleTimeout != 300*time.Second {
		t.Errorf("gvisorRelayIdleTimeout = %v, want 300s", gvisorRelayIdleTimeout)
	}
	if gvisorRelayBufSize < 64*1024 {
		t.Errorf("gvisorRelayBufSize = %d, want >= 64KB", gvisorRelayBufSize)
	}
}

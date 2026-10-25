// Package tun — gvisor_log.go
//
// Portable file logger for the gVisor TUN bridge. Bridge lines are appended
// to the same Go-core log file the Android service passes to startClient, so
// they reach the in-app Logs tab through the existing 250 ms file tailer (no
// logcat needed on-device). No build tag: the file-log test exercises this
// on every platform.
// ==============================================================================
package tun

import (
	"fmt"
	"os"
	"sync"
	"time"
)

// Severities the in-app viewer classifies (WARN/ERROR feed its counters);
// INFO is the default for normal connect/hit/startup lines.
const (
	gvisorLogInfo  = "INFO"
	gvisorLogWarn  = "WARN"
	gvisorLogError = "ERROR"
)

var (
	gvisorLogMu   sync.Mutex
	gvisorLogFile *os.File
)

// gvisorLogOpen opens/creates logPath for appending (create-if-missing,
// O_APPEND). An empty logPath disables the file sink — lines then only reach
// the stderr mirror.
func gvisorLogOpen(logPath string) error {
	gvisorLogMu.Lock()
	defer gvisorLogMu.Unlock()
	if logPath == "" {
		return nil
	}
	f, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0644)
	if err != nil {
		return fmt.Errorf("gvisor log: open %s: %w", logPath, err)
	}
	gvisorLogFile = f
	return nil
}

// gvisorLogLine appends one bridge log line to the file sink:
//
//	2006-01-02 15:04:05 [TUN-GVISOR] [SEVERITY] message
//
// severity must be one of INFO/WARN/ERROR (callers use the constants). The
// same line is mirrored to stderr as a duplicate — never the primary sink.
// Per-packet logging is forbidden (few lines per flow only) so the 250 ms
// tailer and the log buffer stay healthy.
func gvisorLogLine(severity string, msg string) {
	line := time.Now().Format("2006-01-02 15:04:05 ") + fmt.Sprintf("[TUN-GVISOR] [%s] %s\n", severity, msg)
	fmt.Fprintf(os.Stderr, "%s", line)
	gvisorLogMu.Lock()
	defer gvisorLogMu.Unlock()
	if gvisorLogFile != nil {
		_, _ = gvisorLogFile.WriteString(line)
	}
}

// gvisorLogClose flushes and closes the log sink. Safe to call at any time
// (no-op when the logger is disabled or already closed).
func gvisorLogClose() {
	gvisorLogMu.Lock()
	defer gvisorLogMu.Unlock()
	if gvisorLogFile == nil {
		return
	}
	_ = gvisorLogFile.Sync()
	_ = gvisorLogFile.Close()
	gvisorLogFile = nil
}

// ── Bridge logging (thin severity wrappers over the file logger) ──────────────

func gvisorBridgeLog(format string, args ...any) {
	gvisorLogLine(gvisorLogInfo, fmt.Sprintf(format, args...))
}

func gvisorBridgeWarn(format string, args ...any) {
	gvisorLogLine(gvisorLogWarn, fmt.Sprintf(format, args...))
}

func gvisorBridgeErr(format string, args ...any) {
	gvisorLogLine(gvisorLogError, fmt.Sprintf(format, args...))
}

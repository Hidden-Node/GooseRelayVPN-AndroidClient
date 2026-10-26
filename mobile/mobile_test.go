package mobile

import (
	"os"
	"path/filepath"
	"testing"
)

func TestOpenClientLog_AppendsAndPreservesExistingContent(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "vpn.log")

	// 1. Simulate existing log entries written by gVisor bridge or service
	initialContent := "2026/10/26 10:00:00 [TUN-GVISOR] [INFO] bridge active\n"
	if err := os.WriteFile(logPath, []byte(initialContent), 0o644); err != nil {
		t.Fatalf("failed to write initial log: %v", err)
	}

	// 2. Open log via mobile.openClientLog
	f, err := openClientLog(logPath)
	if err != nil {
		t.Fatalf("openClientLog failed: %v", err)
	}

	// 3. Write core stats line
	newContent := "2026/10/26 10:00:05 [stats] active=5 sessions=12/10 bytes=2.1MB/4.5MB polls=100/0\n"
	if _, err := f.WriteString(newContent); err != nil {
		_ = f.Close()
		t.Fatalf("failed to write to log file: %v", err)
	}
	_ = f.Close()

	// 4. Verify initial content was not truncated and new content was appended
	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("failed to read log file: %v", err)
	}
	expected := initialContent + newContent
	if string(data) != expected {
		t.Errorf("log content mismatch.\nGot:\n%s\nWant:\n%s", string(data), expected)
	}
}

func TestOpenClientLog_ConcurrentHandlesAppend(t *testing.T) {
	tmpDir := t.TempDir()
	logPath := filepath.Join(tmpDir, "vpn.log")

	f1, err := openClientLog(logPath)
	if err != nil {
		t.Fatalf("openClientLog f1 failed: %v", err)
	}
	defer f1.Close()

	// Simulate gVisor opening the same path concurrently
	f2, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
	if err != nil {
		t.Fatalf("OpenFile f2 failed: %v", err)
	}
	defer f2.Close()

	if _, err := f2.WriteString("gvisor line 1\n"); err != nil {
		t.Fatalf("f2 write failed: %v", err)
	}
	if _, err := f1.WriteString("stats line 1\n"); err != nil {
		t.Fatalf("f1 write failed: %v", err)
	}

	data, err := os.ReadFile(logPath)
	if err != nil {
		t.Fatalf("read failed: %v", err)
	}
	expected := "gvisor line 1\nstats line 1\n"
	if string(data) != expected {
		t.Errorf("interleaved append mismatch.\nGot:\n%s\nWant:\n%s", string(data), expected)
	}
}

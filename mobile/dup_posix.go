//go:build !windows

package mobile

import "syscall"

// dupFd duplicates a file descriptor so tun2socks and Android each own
// independent copies. When engine.Stop() internally closes the duplicated
// fd, Android's ParcelFileDescriptor still has its original fd to close
// safely — no double-close SIGSEGV. Returns ok=false when Dup fails; the
// caller MUST abort startup in that case rather than proceed with the
// borrowed fd (double-close on stop).
func dupFd(fd int) (int, bool) {
	dup, err := syscall.Dup(fd)
	if err != nil {
		return -1, false
	}
	return dup, true
}

func closeTunFd(fd int32) {
	if fd >= 0 {
		_ = syscall.Close(int(fd))
	}
}

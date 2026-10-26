//go:build !windows

package mobile

import "syscall"

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

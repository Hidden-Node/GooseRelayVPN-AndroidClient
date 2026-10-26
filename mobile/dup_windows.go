//go:build windows

package mobile

func dupFd(fd int) (int, bool) {
	return -1, false
}

func closeTunFd(fd int32) {}

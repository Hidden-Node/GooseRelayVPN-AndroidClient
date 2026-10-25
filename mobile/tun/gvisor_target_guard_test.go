package tun

import "testing"

func TestGvisorBlockedSOCKSTargetTable(t *testing.T) {
	cases := []struct {
		host string
		want bool
	}{
		{"10.10.34.35", true},
		{"10.10.34.36", true},
		{"192.168.1.1", true},
		{"127.0.0.1", true},
		{"0.0.0.0", true},
		{"198.18.0.5", true},
		{"100.64.0.1", true},
		{"224.0.0.1", true},
		{"::1", true},
		{"8.8.8.8", false},
		{"172.217.114.4", false},
		{"m.youtube.com", false},
		{"", true},
		{"localhost", true},
	}
	for _, tc := range cases {
		if got := gvisorIsBlockedSOCKSTarget(tc.host); got != tc.want {
			t.Errorf("gvisorIsBlockedSOCKSTarget(%q) = %v, want %v", tc.host, got, tc.want)
		}
	}
}

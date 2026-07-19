package tun

import (
	"fmt"
	"log"
	"sync"
)

func GetVersion() string {
	return "1.0.0-fakedns-proxy"
}

var (
	bridgeMu     sync.Mutex
	activeProxy  *FakeDNSProxy
	sharedDnsMap *DNSMapper
)

func StartFakeDNSProxy(socksAddr, socksUser, socksPass string) (string, error) {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeProxy != nil {
		return "", fmt.Errorf("FakeDNS proxy already running")
	}
	
	if socksAddr == "" {
		return "", fmt.Errorf("socksAddr cannot be empty")
	}
	
	log.Printf("[TUN-API] Starting FakeDNS SOCKS5 proxy pointing to %s (auth=%v)", socksAddr, socksUser != "")
	
	sharedDnsMap = NewDNSMapper()
	proxy := NewFakeDNSProxy(socksAddr, socksUser, socksPass, sharedDnsMap)
	
	addr, err := proxy.Start()
	if err != nil {
		return "", fmt.Errorf("failed to start FakeDNS proxy: %v", err)
	}
	
	activeProxy = proxy
	log.Printf("[TUN-API] FakeDNS SOCKS5 proxy started successfully on %s", addr)
	
	return addr, nil
}

func StopFakeDNSProxy() {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if activeProxy == nil {
		return
	}
	
	log.Printf("[TUN-API] Stopping FakeDNS proxy")
	activeProxy.Stop()
	activeProxy = nil
	sharedDnsMap = nil
	log.Printf("[TUN-API] FakeDNS proxy stopped")
}

func IsFakeDNSProxyRunning() bool {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	return activeProxy != nil
}

func GetTunBandwidth() (up int64, down int64) {
	// tun2socks engine handles bandwidth stats, this is dummy now
	return 0, 0
}

func GetDNSMapping(fakeIP string) string {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if sharedDnsMap == nil {
		return ""
	}
	
	hostname, ok := sharedDnsMap.GetHostname(fakeIP)
	if !ok {
		return ""
	}
	
	return hostname
}

func GetDNSMappingCount() int {
	bridgeMu.Lock()
	defer bridgeMu.Unlock()
	
	if sharedDnsMap == nil {
		return 0
	}
	
	sharedDnsMap.mu.RLock()
	count := len(sharedDnsMap.hostnameToIP)
	sharedDnsMap.mu.RUnlock()
	
	return count
}

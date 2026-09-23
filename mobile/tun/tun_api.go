package tun

import (
	"fmt"
	"log"
	"sync"
)

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

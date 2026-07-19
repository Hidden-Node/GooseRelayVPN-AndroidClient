package tun

import (
	"context"
	"encoding/binary"
	"fmt"
	"io"
	"net"
	"sync"
)

type FakeDNSProxy struct {
	RealSocksAddr string
	SocksUser     string
	SocksPass     string
	LocalPort     int
	dnsMap        *DNSMapper
	listener      net.Listener
	ctx           context.Context
	cancel        context.CancelFunc
	wg            sync.WaitGroup
}

func NewFakeDNSProxy(realSocksAddr, socksUser, socksPass string, dnsMap *DNSMapper) *FakeDNSProxy {
	ctx, cancel := context.WithCancel(context.Background())
	return &FakeDNSProxy{
		RealSocksAddr: realSocksAddr,
		SocksUser:     socksUser,
		SocksPass:     socksPass,
		dnsMap:        dnsMap,
		ctx:           ctx,
		cancel:        cancel,
	}
}

// dialRealSocks opens a TCP connection to the upstream SOCKS5 server and
// completes the auth handshake. If [SocksUser] and [SocksPass] are both
// set, the user/pass method (0x02) is offered; otherwise NO_AUTH (0x00).
// Returns the negotiated upstream connection, or nil + an error.
func (p *FakeDNSProxy) dialRealSocks() (net.Conn, error) {
	realConn, err := net.Dial("tcp", p.RealSocksAddr)
	if err != nil {
		return nil, err
	}

	// Greeting: offer NO_AUTH and (if configured) USERNAME/PASSWORD.
	// RFC 1928 + RFC 1929.
	var greeting []byte
	if p.SocksUser != "" && p.SocksPass != "" {
		greeting = []byte{5, 2, 0, 2} // VER=5, NMETHODS=2, NO_AUTH, USER/PASS
	} else {
		greeting = []byte{5, 1, 0} // VER=5, NMETHODS=1, NO_AUTH
	}
	if _, err := realConn.Write(greeting); err != nil {
		realConn.Close()
		return nil, err
	}

	resp := make([]byte, 2)
	if _, err := io.ReadFull(realConn, resp); err != nil {
		realConn.Close()
		return nil, err
	}
	if resp[0] != 5 {
		realConn.Close()
		return nil, fmt.Errorf("upstream SOCKS5 version mismatch: %d", resp[0])
	}

	switch resp[1] {
	case 0:
		// NO_AUTH selected. Done.
		return realConn, nil
	case 2:
		// USERNAME/PASSWORD selected — proceed with RFC 1929 sub-negotiation.
		if p.SocksUser == "" || p.SocksPass == "" {
			realConn.Close()
			return nil, fmt.Errorf("upstream requires auth but no credentials configured")
		}
		user := []byte(p.SocksUser)
		pass := []byte(p.SocksPass)
		if len(user) > 255 || len(pass) > 255 {
			realConn.Close()
			return nil, fmt.Errorf("socks credentials too long")
		}
		req := []byte{1, byte(len(user))}
		req = append(req, user...)
		req = append(req, byte(len(pass)))
		req = append(req, pass...)
		if _, err := realConn.Write(req); err != nil {
			realConn.Close()
			return nil, err
		}
		authResp := make([]byte, 2)
		if _, err := io.ReadFull(realConn, authResp); err != nil {
			realConn.Close()
			return nil, err
		}
		if authResp[1] != 0 {
			realConn.Close()
			return nil, fmt.Errorf("socks auth rejected (status %d)", authResp[1])
		}
		return realConn, nil
	default:
		realConn.Close()
		return nil, fmt.Errorf("upstream selected unsupported auth method %d", resp[1])
	}
}

func (p *FakeDNSProxy) Start() (string, error) {
	l, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return "", err
	}
	p.listener = l
	p.LocalPort = l.Addr().(*net.TCPAddr).Port

	p.wg.Add(1)
	go p.acceptLoop()

	return l.Addr().String(), nil
}

func (p *FakeDNSProxy) Stop() {
	p.cancel()
	if p.listener != nil {
		p.listener.Close()
	}
}

func (p *FakeDNSProxy) acceptLoop() {
	defer p.wg.Done()
	for {
		conn, err := p.listener.Accept()
		if err != nil {
			if p.ctx.Err() != nil {
				return
			}
			continue
		}
		p.wg.Add(1)
		go func(c net.Conn) {
			defer p.wg.Done()
			p.handleConnection(c)
		}(conn)
	}
}

func (p *FakeDNSProxy) handleConnection(conn net.Conn) {
	defer conn.Close()

	// Read greeting
	header := make([]byte, 2)
	if _, err := io.ReadFull(conn, header); err != nil {
		return
	}
	methods := make([]byte, header[1])
	if _, err := io.ReadFull(conn, methods); err != nil {
		return
	}

	// Send auth response: NO_AUTH
	if _, err := conn.Write([]byte{5, 0}); err != nil {
		return
	}

	// Read request
	reqHeader := make([]byte, 4)
	if _, err := io.ReadFull(conn, reqHeader); err != nil {
		return
	}

	cmd := reqHeader[1]
	atyp := reqHeader[3]

	var targetAddr []byte
	if atyp == 1 { // IPV4
		ip := make([]byte, 4)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return
		}
		targetAddr = ip
	} else if atyp == 3 { // DOMAIN
		l := make([]byte, 1)
		if _, err := io.ReadFull(conn, l); err != nil {
			return
		}
		dom := make([]byte, l[0])
		if _, err := io.ReadFull(conn, dom); err != nil {
			return
		}
		targetAddr = append(l, dom...)
	} else if atyp == 4 { // IPV6
		ip := make([]byte, 16)
		if _, err := io.ReadFull(conn, ip); err != nil {
			return
		}
		targetAddr = ip
	}

	targetPort := make([]byte, 2)
	if _, err := io.ReadFull(conn, targetPort); err != nil {
		return
	}

	// FakeDNS Interception for TCP CONNECT
	if cmd == 1 && atyp == 1 {
		ipStr := net.IP(targetAddr).String()
		if hostname, ok := p.dnsMap.GetHostname(ipStr); ok {
			atyp = 3
			l := byte(len(hostname))
			targetAddr = append([]byte{l}, []byte(hostname)...)
		}
	}

	if cmd == 3 { // UDP ASSOCIATE
		p.handleUDPAssociate(conn, atyp, targetAddr, targetPort)
		return
	}

	// Dial Real SOCKS (with auth if configured)
	realConn, err := p.dialRealSocks()
	if err != nil {
		_, _ = conn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer realConn.Close()

	req := []byte{5, 1, 0, atyp}
	req = append(req, targetAddr...)
	req = append(req, targetPort...)
	if _, err := realConn.Write(req); err != nil {
		return
	}

	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(realConn, replyHeader); err != nil {
		return
	}
	if _, err := conn.Write(replyHeader); err != nil {
		return
	}

	var bndAddr []byte
	if replyHeader[3] == 1 {
		bndAddr = make([]byte, 4)
	} else if replyHeader[3] == 3 {
		l := make([]byte, 1)
		io.ReadFull(realConn, l)
		bndAddr = make([]byte, l[0])
		bndAddr = append(l, bndAddr...)
	} else if replyHeader[3] == 4 {
		bndAddr = make([]byte, 16)
	}
	if len(bndAddr) > 0 {
		io.ReadFull(realConn, bndAddr)
		conn.Write(bndAddr)
	}

	bndPort := make([]byte, 2)
	io.ReadFull(realConn, bndPort)
	conn.Write(bndPort)

	go io.Copy(realConn, conn)
	io.Copy(conn, realConn)
}

func (p *FakeDNSProxy) handleUDPAssociate(tcpConn net.Conn, atyp byte, targetAddr []byte, targetPort []byte) {
	realConn, err := p.dialRealSocks()
	if err != nil {
		_, _ = tcpConn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer realConn.Close()

	req := []byte{5, 3, 0, atyp}
	req = append(req, targetAddr...)
	req = append(req, targetPort...)
	if _, err := realConn.Write(req); err != nil {
		return
	}

	replyHeader := make([]byte, 4)
	if _, err := io.ReadFull(realConn, replyHeader); err != nil {
		return
	}

	var bndAddr []byte
	if replyHeader[3] == 1 {
		bndAddr = make([]byte, 4)
	} else if replyHeader[3] == 3 {
		l := make([]byte, 1)
		io.ReadFull(realConn, l)
		dom := make([]byte, l[0])
		io.ReadFull(realConn, dom)
		bndAddr = append(l, dom...)
	} else if replyHeader[3] == 4 {
		bndAddr = make([]byte, 16)
	}
	if len(bndAddr) > 0 {
		io.ReadFull(realConn, bndAddr)
	}

	bndPortBuf := make([]byte, 2)
	io.ReadFull(realConn, bndPortBuf)

	var realUdpAddr *net.UDPAddr
	if replyHeader[3] == 1 {
		realUdpAddr = &net.UDPAddr{IP: net.IP(bndAddr), Port: int(binary.BigEndian.Uint16(bndPortBuf))}
	}
	if realUdpAddr != nil && realUdpAddr.IP.IsUnspecified() {
		host, _, _ := net.SplitHostPort(p.RealSocksAddr)
		realUdpAddr.IP = net.ParseIP(host)
	}

	localUdp, err := net.ListenUDP("udp", &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: 0})
	if err != nil {
		tcpConn.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
		return
	}
	defer localUdp.Close()

	localPort := localUdp.LocalAddr().(*net.UDPAddr).Port

	reply := []byte{5, 0, 0, 1, 127, 0, 0, 1}
	pBuf := make([]byte, 2)
	binary.BigEndian.PutUint16(pBuf, uint16(localPort))
	reply = append(reply, pBuf...)
	if _, err := tcpConn.Write(reply); err != nil {
		return
	}

	go func() {
		buf := make([]byte, 65535)
		var tun2socksAddr *net.UDPAddr
		for {
			n, rAddr, err := localUdp.ReadFromUDP(buf)
			if err != nil {
				return
			}

			if realUdpAddr != nil && rAddr.IP.Equal(realUdpAddr.IP) && rAddr.Port == realUdpAddr.Port {
				if tun2socksAddr != nil {
					localUdp.WriteToUDP(buf[:n], tun2socksAddr)
				}
				continue
			}

			tun2socksAddr = rAddr

			if n < 4 || buf[2] != 0 {
				continue
			}
			frag := buf[2]
			if frag != 0 {
				continue
			}

			atyp := buf[3]
			var offset int
			var tPort uint16

			if atyp == 1 {
				offset = 10
				if n < offset {
					continue
				}
				tPort = binary.BigEndian.Uint16(buf[8:10])
			} else if atyp == 3 {
				l := int(buf[4])
				offset = 5 + l + 2
				if n < offset {
					continue
				}
				tPort = binary.BigEndian.Uint16(buf[5+l : offset])
			} else if atyp == 4 {
				offset = 22
				if n < offset {
					continue
				}
				tPort = binary.BigEndian.Uint16(buf[20:22])
			} else {
				continue
			}

			if tPort == 53 {
				dnsQuery := buf[offset:n]
				hostname := parseDNSQuery(dnsQuery)
				if hostname != "" {
					fakeIP := p.dnsMap.GetFakeIP(hostname)
					resp := buildDNSResponse(dnsQuery, fakeIP)
					if resp != nil {
						fullResp := append(buf[:offset], resp...)
						localUdp.WriteToUDP(fullResp, rAddr)
					}
				}
				continue
			}

			if realUdpAddr != nil {
				localUdp.WriteToUDP(buf[:n], realUdpAddr)
			}
		}
	}()

	// Keep TCP connection open
	io.Copy(io.Discard, tcpConn)
}

func parseDNSQuery(query []byte) string {
	if len(query) < 12 {
		return ""
	}
	pos := 12
	labels := []string{}
	for pos < len(query) {
		length := int(query[pos])
		if length == 0 {
			break
		}
		if length > 63 || pos+1+length > len(query) {
			return ""
		}
		pos++
		label := string(query[pos : pos+length])
		labels = append(labels, label)
		pos += length
	}
	if len(labels) == 0 {
		return ""
	}
	hostname := ""
	for i, label := range labels {
		if i > 0 {
			hostname += "."
		}
		hostname += label
	}
	return hostname
}

func buildDNSResponse(query []byte, fakeIP string) []byte {
	if len(query) < 12 {
		return nil
	}
	response := make([]byte, len(query)+16)
	copy(response, query)
	flags := binary.BigEndian.Uint16(response[2:4])
	flags |= 0x8400
	binary.BigEndian.PutUint16(response[2:4], flags)
	binary.BigEndian.PutUint16(response[6:8], 1)
	pos := len(query)
	response[pos] = 0xC0
	response[pos+1] = 0x0C
	pos += 2
	binary.BigEndian.PutUint16(response[pos:pos+2], 1)
	binary.BigEndian.PutUint16(response[pos+2:pos+4], 1)
	pos += 4
	binary.BigEndian.PutUint32(response[pos:pos+4], 60)
	pos += 4
	binary.BigEndian.PutUint16(response[pos:pos+2], 4)
	pos += 2
	ip := net.ParseIP(fakeIP).To4()
	if ip == nil {
		return nil
	}
	copy(response[pos:pos+4], ip)
	pos += 4
	return response[:pos]
}

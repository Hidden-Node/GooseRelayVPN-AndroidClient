package mobile

import (
	"context"
	"fmt"
	"io"
	"log"
	"net"
	"os"
	"path/filepath"
	"sync"
	"syscall"
	"time"

	"github.com/kianmhz/GooseRelayVPN/mobile/tun"
	"github.com/kianmhz/GooseRelayVPN/internal/carrier"
	"github.com/kianmhz/GooseRelayVPN/internal/config"
	"github.com/kianmhz/GooseRelayVPN/internal/session"
	isocks "github.com/kianmhz/GooseRelayVPN/internal/socks"
	"github.com/things-go/go-socks5"
	"github.com/things-go/go-socks5/statute"
	"github.com/xjasonlyu/tun2socks/v2/engine"
)

var (
	mu               sync.Mutex
	cancelFn         context.CancelFunc
	socksLn          net.Listener
	running          bool
	tunActive        bool
	tunBridgeRunning bool
	clientDone       chan struct{}
	engineMu         sync.Mutex // Protects tun2socks engine Start/Stop

	// tunOwnedFd is the most recently dup'd TUN fd inserted into the
	// tun2socks engine. It is -1 when no fd is owned. Guarded by engineMu
	// (so it is always accessed together with engine.Start/Stop).
	tunOwnedFd int32 = -1
)

var (
	totalUploadBytes   int64
	totalDownloadBytes int64
	bytesMu            sync.RWMutex
)

func addUploadBytes(n int64) {
	bytesMu.Lock()
	totalUploadBytes += n
	bytesMu.Unlock()
}

func addDownloadBytes(n int64) {
	bytesMu.Lock()
	totalDownloadBytes += n
	bytesMu.Unlock()
}

type trackingConn struct {
	net.Conn
}

func (t *trackingConn) Read(b []byte) (n int, err error) {
	n, err = t.Conn.Read(b)
	if n > 0 {
		addDownloadBytes(int64(n))
	}
	return
}

func (t *trackingConn) Write(b []byte) (n int, err error) {
	n, err = t.Conn.Write(b)
	if n > 0 {
		addUploadBytes(int64(n))
	}
	return
}

// Bandwidth holds upload and download counters.
type Bandwidth struct {
	Up   int64
	Down int64
}

func StartClient(configPath string, logPath string) error {
	mu.Lock()
	if running {
		mu.Unlock()
		return fmt.Errorf("client is already running")
	}
	mu.Unlock()

	bytesMu.Lock()
	totalUploadBytes = 0
	totalDownloadBytes = 0
	bytesMu.Unlock()

	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		return fmt.Errorf("create log directory: %w", err)
	}
	logFile, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o644)
	if err != nil {
		return fmt.Errorf("open log file: %w", err)
	}
	defer logFile.Close()
	log.SetFlags(0)
	log.SetOutput(logFile)

	cfg, err := config.LoadClient(configPath)
	if err != nil {
		return err
	}

	carr, err := carrier.New(carrier.Config{
		ScriptURLs:       cfg.ScriptURLs,
		ScriptAccounts:   cfg.ScriptAccounts,
		AESKeyHex:        cfg.AESKeyHex,
		DebugTiming:      cfg.DebugTiming,
		CoalesceStep:     time.Duration(cfg.CoalesceStepMs) * time.Millisecond,
		CoalesceMax:      time.Duration(cfg.CoalesceMaxMs) * time.Millisecond,
		IdleSlotsPerBucket: cfg.IdleSlotsPerBucket,
		Fronting: carrier.FrontingConfig{
			GoogleIP: cfg.GoogleIP,
			SNIHosts: cfg.SNIHosts,
		},
	})
	if err != nil {
		return fmt.Errorf("carrier init failed: %w", err)
	}

	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	mu.Lock()
	cancelFn = cancel
	clientDone = done
	running = true
	mu.Unlock()

	go func() {
		if err := carr.Run(ctx); err != nil && ctx.Err() == nil {
			log.Printf("[client] carrier run error: %v", err)
		}
	}()

	factory := isocks.SessionFactory(func(target string) *session.Session {
		return carr.NewSession(target)
	})

	var serverOpts []socks5.Option
	serverOpts = append(serverOpts,
		socks5.WithDial(func(_ context.Context, _, addr string) (net.Conn, error) {
			s := factory(addr)
			log.Printf("[socks] new session %x for %s", s.ID[:4], addr)
			return &trackingConn{Conn: isocks.NewVirtualConn(s)}, nil
		}),
		socks5.WithAssociateHandle(func(_ context.Context, w io.Writer, _ *socks5.Request) error {
			_ = socks5.SendReply(w, statute.RepCommandNotSupported, nil)
			return fmt.Errorf("UDP associate not supported")
		}),
		socks5.WithResolver(noopResolver{}),
	)

	if cfg.SocksUser != "" && cfg.SocksPass != "" {
		serverOpts = append(serverOpts, socks5.WithAuthMethods([]socks5.Authenticator{
			socks5.UserPassAuthenticator{
				Credentials: socks5.StaticCredentials{
					cfg.SocksUser: cfg.SocksPass,
				},
			},
		}))
		log.Printf("[socks] authentication enabled: user=%s", cfg.SocksUser)
	}

	server := socks5.NewServer(serverOpts...)

	ln, lerr := net.Listen("tcp", cfg.ListenAddr)
	if lerr != nil {
		mu.Lock()
		running = false
		cancelFn = nil
		clientDone = nil
		mu.Unlock()
		return lerr
	}
	mu.Lock()
	socksLn = ln
	mu.Unlock()

	go func() {
		<-ctx.Done()
		mu.Lock()
		if socksLn != nil {
			_ = socksLn.Close()
		}
		mu.Unlock()
	}()

	err = server.Serve(ln)

	close(done)

	mu.Lock()
	running = false
	cancelFn = nil
	clientDone = nil
	socksLn = nil
	mu.Unlock()
	return err
}

// dupFd duplicates a file descriptor so tun2socks and Android each own
// independent copies.  When engine.Stop() internally closes the duplicated fd,
// Android's ParcelFileDescriptor still has its original fd to close safely —
// no double-close SIGSEGV.
func dupFd(fd int) int {
	dup, err := syscall.Dup(fd)
	if err != nil {
		return fd
	}
	return dup
}

func StopClient() {
	mu.Lock()
	cancel := cancelFn
	done := clientDone
	ln := socksLn
	cancelFn = nil
	socksLn = nil
	mu.Unlock()

	if cancel != nil {
		cancel()
	}
	if ln != nil {
		_ = ln.Close()
	}

	if done != nil {
		select {
		case <-done:
		case <-time.After(3 * time.Second):
		}
	}

	StopTun()
	StopTunBridge()
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

func StartTun(fd int64, proxyAddr string) {
	safeFd := dupFd(int(fd))

	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", safeFd),
		MTU:    1500,
	}

	engineMu.Lock()
	// If a previous fd is still tracked, close it now. This guards
	// against the (unlikely) case where StartTun is called twice without
	// an intervening StopTun.
	if tunOwnedFd >= 0 {
		_ = syscall.Close(int(tunOwnedFd))
	}
	engine.Insert(key)
	engine.Start()
	tunOwnedFd = int32(safeFd)
	engineMu.Unlock()

	mu.Lock()
	tunActive = true
	mu.Unlock()
}

func StopTun() {
	mu.Lock()
	if !tunActive {
		mu.Unlock()
		return
	}
	tunActive = false
	mu.Unlock()

	engineMu.Lock()
	defer engineMu.Unlock()
	func() {
		defer func() { recover() }()
		engine.Stop()
	}()
	if tunOwnedFd >= 0 {
		_ = syscall.Close(int(tunOwnedFd))
		tunOwnedFd = -1
	}
}

// TUN Bridge wrapper functions (calls mobile/tun subpackage)

// StartTunBridge starts the TUN bridge with DNS interception using FakeDNS proxy.
func StartTunBridge(tunFd int64, mtu int64, socksAddr string) error {
	proxyAddr, err := tun.StartFakeDNSProxy(socksAddr)
	if err != nil {
		return err
	}
	
	safeFd := dupFd(int(tunFd))

	key := &engine.Key{
		Proxy:  "socks5://" + proxyAddr,
		Device: fmt.Sprintf("fd://%d", safeFd),
		MTU:    int(mtu),
	}

	engineMu.Lock()
	if tunOwnedFd >= 0 {
		_ = syscall.Close(int(tunOwnedFd))
	}
	engine.Insert(key)
	engine.Start()
	tunOwnedFd = int32(safeFd)
	engineMu.Unlock()
	
	mu.Lock()
	tunBridgeRunning = true
	mu.Unlock()

	return nil
}

// StopTunBridge stops the TUN bridge
func StopTunBridge() {
	mu.Lock()
	if !tunBridgeRunning {
		mu.Unlock()
		return
	}
	tunBridgeRunning = false
	mu.Unlock()

	engineMu.Lock()
	func() {
		defer func() { recover() }()
		engine.Stop()
	}()
	if tunOwnedFd >= 0 {
		_ = syscall.Close(int(tunOwnedFd))
		tunOwnedFd = -1
	}
	engineMu.Unlock()
	
	tun.StopFakeDNSProxy()
}

// IsTunBridgeRunning returns true if bridge is active
func IsTunBridgeRunning() bool {
	return tun.IsFakeDNSProxyRunning()
}

// GetTunBandwidth returns upload and download bytes.
func GetTunBandwidth() *Bandwidth {
	bytesMu.RLock()
	defer bytesMu.RUnlock()
	return &Bandwidth{
		Up:   totalUploadBytes,
		Down: totalDownloadBytes,
	}
}

// GetDNSMapping returns the hostname for a fake IP
func GetDNSMapping(fakeIP string) string {
	return tun.GetDNSMapping(fakeIP)
}

// GetDNSMappingCount returns the number of DNS mappings
func GetDNSMappingCount() int {
	return tun.GetDNSMappingCount()
}

// GetTunVersion returns the TUN module version
func GetTunVersion() string {
	return tun.GetVersion()
}

type noopResolver struct{}

func (noopResolver) Resolve(ctx context.Context, _ string) (context.Context, net.IP, error) {
	return ctx, nil, nil
}

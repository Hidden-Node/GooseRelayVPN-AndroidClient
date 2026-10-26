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
	"time"

	"github.com/kianmhz/GooseRelayVPN/internal/carrier"
	"github.com/kianmhz/GooseRelayVPN/internal/config"
	"github.com/kianmhz/GooseRelayVPN/internal/session"
	isocks "github.com/kianmhz/GooseRelayVPN/internal/socks"
	"github.com/kianmhz/GooseRelayVPN/mobile/tun"
	"github.com/things-go/go-socks5"
	"github.com/things-go/go-socks5/statute"
)

var (
	mu         sync.Mutex
	cancelFn   context.CancelFunc
	socksLn    net.Listener
	running    bool
	clientDone chan struct{}
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

func openClientLog(logPath string) (*os.File, error) {
	if err := os.MkdirAll(filepath.Dir(logPath), 0o755); err != nil {
		return nil, fmt.Errorf("create log directory: %w", err)
	}
	return os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o644)
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

	logFile, err := openClientLog(logPath)
	if err != nil {
		return fmt.Errorf("open log file: %w", err)
	}
	origFlags := log.Flags()
	origWriter := log.Writer()
	defer func() {
		log.SetFlags(origFlags)
		log.SetOutput(origWriter)
		_ = logFile.Close()
	}()
	log.SetFlags(0)
	log.SetOutput(logFile)

	cfg, err := config.LoadClient(configPath)
	if err != nil {
		return err
	}

	carr, err := carrier.New(carrier.Config{
		ScriptURLs:         cfg.ScriptURLs,
		ScriptAccounts:     cfg.ScriptAccounts,
		AESKeyHex:          cfg.AESKeyHex,
		DebugTiming:        cfg.DebugTiming,
		CoalesceStep:       time.Duration(cfg.CoalesceStepMs) * time.Millisecond,
		CoalesceMax:        time.Duration(cfg.CoalesceMaxMs) * time.Millisecond,
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
		cancel() // stop carr.Run(ctx); otherwise the goroutine leaks holding creds
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
		case <-time.After(1 * time.Second):
			// The carrier shutdown took longer than 1s. The outer
			// Android caller (GooseRelayVpnService.stopVpn) caps total
			// disconnect with a 5s outer join, so we return promptly
			// here and let the outer caller decide whether to log a
			// "stop timed out" message. StopTunGvisor below is
			// idempotent and safe to call even if the carrier is still
			// winding down.
			log.Printf("[client] carrier shutdown exceeded 1s, proceeding with TUN teardown")
		}
	}

	StopTunGvisor()
}

func IsRunning() bool {
	mu.Lock()
	defer mu.Unlock()
	return running
}

// StartTunGvisor starts the gVisor TUN bridge (see mobile/tun).
// The bridge answers DNS locally with fake IPs; no resolver list is needed.
// logPath is the same Go-core log file path the service passes to
// StartClient - bridge lines are appended there so they reach the in-app
// Logs tab via the existing tailLogFile (no logcat needed on-device).
func StartTunGvisor(tunFd int64, mtu int64, socksAddr string, socksUser string, socksPass string, logPath string) error {
	return tun.StartTunGvisor(tunFd, mtu, socksAddr, socksUser, socksPass, logPath)
}

// StopTunGvisor stops the gVisor TUN bridge. Safe to call multiple times.
func StopTunGvisor() { tun.StopTunGvisor() }

// IsTunGvisorRunning reports whether the bridge lifecycle is live. The
// Android service polls this from its watchdog (plan 054) and stops the VPN
// if the engine disappears while the UI still shows "connected".
func IsTunGvisorRunning() bool { return tun.IsTunGvisorRunning() }

// GetTunBandwidth returns upload and download bytes.
func GetTunBandwidth() *Bandwidth {
	bytesMu.RLock()
	defer bytesMu.RUnlock()
	return &Bandwidth{
		Up:   totalUploadBytes,
		Down: totalDownloadBytes,
	}
}

type noopResolver struct{}

func (noopResolver) Resolve(ctx context.Context, _ string) (context.Context, net.IP, error) {
	return ctx, nil, nil
}

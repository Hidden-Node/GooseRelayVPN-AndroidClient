module github.com/kianmhz/GooseRelayVPN

go 1.25.0

require github.com/things-go/go-socks5 v0.1.1

require (
	github.com/klauspost/compress v1.17.11
	github.com/xjasonlyu/tun2socks/v2 v2.6.0
	golang.org/x/net v0.52.0
)

require (
	github.com/ajg/form v1.5.1 // indirect
	github.com/docker/go-units v0.5.0 // indirect
	github.com/go-chi/chi/v5 v5.2.1 // indirect
	github.com/go-chi/cors v1.2.1 // indirect
	github.com/go-chi/render v1.0.3 // indirect
	github.com/go-gost/relay v0.5.0 // indirect
	github.com/google/btree v1.1.3 // indirect
	github.com/google/shlex v0.0.0-20191202100458-e7afc7fbc510 // indirect
	github.com/google/uuid v1.6.0 // indirect
	github.com/gorilla/schema v1.4.1 // indirect
	github.com/gorilla/websocket v1.5.3 // indirect
	github.com/kardianos/osext v0.0.0-20190222173326-2bc1f35cddc0 // indirect
	go.uber.org/atomic v1.11.0 // indirect
	go.uber.org/automaxprocs v1.6.0 // indirect
	go.uber.org/multierr v1.11.0 // indirect
	go.uber.org/zap v1.27.0 // indirect
	golang.org/x/crypto v0.49.0 // indirect
	golang.org/x/mobile v0.0.0-20260312152759-81488f6aeb60 // indirect
	golang.org/x/mod v0.34.0 // indirect
	golang.org/x/sync v0.20.0 // indirect
	golang.org/x/sys v0.42.0 // indirect
	golang.org/x/text v0.35.0 // indirect
	golang.org/x/time v0.11.0 // indirect
	golang.org/x/tools v0.43.0 // indirect
	golang.zx2c4.com/wintun v0.0.0-20230126152724-0fa3db229ce2 // indirect
	golang.zx2c4.com/wireguard v0.0.0-20250521234502-f333402bd9cb // indirect
	gopkg.in/yaml.v3 v3.0.1 // indirect
	gvisor.dev/gvisor v0.0.0-20250523182742-eede7a881b20 // indirect
)

replace github.com/mitchellh/osext => github.com/kardianos/osext v0.0.0-20190222173326-2bc1f35cddc0

exclude github.com/mitchellh/osext v0.0.0-20151018003038-5e2d6d41470f

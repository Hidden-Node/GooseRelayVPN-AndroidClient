module github.com/kianmhz/GooseRelayVPN

go 1.25.0

require github.com/things-go/go-socks5 v0.1.1

require (
	github.com/klauspost/compress v1.17.11
	golang.org/x/net v0.52.0
)

require (
	github.com/google/btree v1.1.3 // indirect
	golang.org/x/sys v0.42.0 // indirect
	golang.org/x/text v0.35.0 // indirect
	golang.org/x/time v0.11.0 // indirect
	gvisor.dev/gvisor v0.0.0-20250523182742-eede7a881b20
)

replace github.com/mitchellh/osext => github.com/kardianos/osext v0.0.0-20190222173326-2bc1f35cddc0

exclude github.com/mitchellh/osext v0.0.0-20151018003038-5e2d6d41470f

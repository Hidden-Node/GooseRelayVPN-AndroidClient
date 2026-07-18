#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

MOBILE_TOOLS_VERSION="v0.0.0-20260312152759-81488f6aeb60"

# Always install a pinned, known-good gomobile/gobind pair.
# go install skipped - using patched gomobile
go install "golang.org/x/mobile/cmd/gomobile@${MOBILE_TOOLS_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${MOBILE_TOOLS_VERSION}"

# Ensure module dependency is available for gomobile bind
GO111MODULE=on go get golang.org/x/mobile@${MOBILE_TOOLS_VERSION}
GO111MODULE=on go get golang.org/x/mobile/bind@${MOBILE_TOOLS_VERSION}

export PATH="$(go env GOPATH)/bin:$PATH"
GO111MODULE=on gomobile init

mkdir -p android/app/libs

gomobile bind \
  -v \
  -target=android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 21 \
  -o android/app/libs/gooserelayvpn.aar \
  ./mobile/

echo "Built android/app/libs/gooserelayvpn.aar"

# TUN module is now included in gooserelayvpn.aar (no separate build needed)

#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
cd "$ROOT_DIR"

MOBILE_TOOLS_VERSION="$(tr -d '\r' < "$SCRIPT_DIR/gomobile.version")"

# The gomobile tool needs golang.org/x/mobile in the module graph, but an
# Android build must never modify the repository's go.mod/go.sum. Build in
# an isolated copy of the WORKING TREE (not of HEAD: local rebuilds must
# include uncommitted changes) and let the copy take the mutation.
BUILD_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/gooserelayvpn-gomobile-XXXXXX")"
cleanup() { rm -rf "$BUILD_ROOT"; }
trap cleanup EXIT

# The excludes keep the copy small AND keep gitignored secrets
# (client_config.json, server_config.json, *.token) out of the temp tree.
tar -C "$ROOT_DIR" \
    --exclude='./.git' \
    --exclude='./android/.gradle' \
    --exclude='./android/app/build' \
    --exclude='./android/build' \
    --exclude='./android/app/libs' \
    --exclude='./client_config.json' \
    --exclude='./server_config.json' \
    --exclude='*.token' \
    -cf - . | tar -C "$BUILD_ROOT" -xf -

# Always install a pinned, known-good gomobile/gobind pair.
go install "golang.org/x/mobile/cmd/gomobile@${MOBILE_TOOLS_VERSION}"
go install "golang.org/x/mobile/cmd/gobind@${MOBILE_TOOLS_VERSION}"

export PATH="$(go env GOPATH)/bin:$PATH"
GO111MODULE=on gomobile init

cd "$BUILD_ROOT"

# Resolve gomobile's bind dependency only in this temporary build tree.
GO111MODULE=on go get golang.org/x/mobile@${MOBILE_TOOLS_VERSION}
GO111MODULE=on go mod download

mkdir -p "$ROOT_DIR/android/app/libs"

gomobile bind \
  -v \
  -target=android/arm64,android/arm,android/amd64,android/386 \
  -androidapi 21 \
  -o "$ROOT_DIR/android/app/libs/gooserelayvpn.aar" \
  ./mobile/

echo "Built android/app/libs/gooserelayvpn.aar"

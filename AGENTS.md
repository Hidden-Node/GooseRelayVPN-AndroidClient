# AGENTS.md

Operating context for AI agents (opencode, Cursor, Claude Code, etc.)
working on this repository. Read this file first; it covers what the
project is, how to build/verify it, and the conventions to follow.

## What this repo is

`GooseRelayVPN-AndroidClient` is the Android client for the
GooseRelayVPN project. It wraps the upstream Go core (in `internal/`,
shared with the server) in an Android `VpnService` and exposes a
Jetpack Compose UI for VPN lifecycle, profile management, logs, and
settings.

Upstream Go core: <https://github.com/kianmhz/GooseRelayVPN>
This client: <https://github.com/ArashAfkandeh/GooseRelayVPN-AndroidClient>

## Architecture (Android side)

```
┌─────────────────────────────────────────────┐
│   Android UI Layer (Compose)                │
│  Home │ Profiles │ Settings │ Logs │ Info   │
└─────────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────┐
│   ViewModels & Repository Layer             │
│  Room │ DataStore │ Hilt DI                 │
└─────────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────┐
│   VPN Service & Go Core                     │
│  tun2socks │ GooseRelay Core (Go mobile)    │
└─────────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────┐
│   Network Layer                             │
│  SOCKS5 → Google Apps Script → VPS exit     │
└─────────────────────────────────────────────┘
```

- `android/` — Gradle project (`settings.gradle.kts`, `build.gradle.kts`, `app/`). Kotlin 2.1.0,
  AGP 8.13.0, `minSdk=21`, `targetSdk=36`, `compileSdk=36`, JVM 17.
- `android/app/src/main/java/com/gooserelay/gooserelayvpn/` — Kotlin source:
  - `service/` — `GooseRelayVpnService` (the VpnService),
    `VpnTileService` (Quick Settings tile), `BootReceiver` (boot,
    currently a no-op).
  - `ui/` — Compose screens and ViewModels, split by feature
    (`home/`, `profiles/`, `settings/`, `logs/`, `info/`,
    `navigation/`).
  - `data/local/` — Room (`AppDatabase` v4, `ProfileEntity`,
    `ProfileDao`, `ProfileMigrations`).
  - `data/repository/` — `ProfileRepository` (Hilt `@Singleton`).
  - `dns/` — Kotlin FakeDNS interceptor (separate from the Go-side
    FakeDNS in `mobile/tun/`).
  - `di/` — Hilt `AppModule`.
  - `util/` — `VpnManager` (singleton bridge between UI and Go
    core), `ConfigGenerator` (profile → JSON config),
    `GlobalSettingsStore` (DataStore-backed).
- `mobile/` — Go mobile bridge, gomobile bind target:
  - `mobile/mobile.go` — `StartClient` / `StopClient` /
    `StartTun` / `StopTun` / `StartTunBridge` / `StopTunBridge`
    exported to Kotlin via `mobile.Mobile.*`.
  - `mobile/tun/` — FakeDNS proxy (`fakedns_proxy.go`),
    DNS mapper (`dns_mapper.go`), `tun_api.go` (exported
    `StartFakeDNSProxy` etc.).
- `internal/` — upstream Go core (carrier, session, socks, config,
  etc.). **Treat as read-only when working on the Android client.**
- `apps_script/` — Google Apps Script deployment that fronts the
  carrier traffic over Google infrastructure.

## How to build

```bash
# 1. Build the Go mobile AAR (requires Go 1.25+, NDK installed).
# On Windows: use build_go_mobile.bat; on Unix: build_go_mobile.sh.
bash ./android/build_go_mobile.sh
# Output: android/app/libs/gooserelayvpn.aar

# 2. Build the debug APK.
cd android
./gradlew :app:assembleDebug
# Output: android/app/build/outputs/apk/debug/GooseRelayVPN.apk

# 3. Build the release AAB (requires signing config in local.properties).
./gradlew :app:bundleRelease
```

## How to verify

```bash
cd android

# Unit tests (JVM, no emulator needed).
./gradlew :app:testDebugUnitTest --stacktrace

# Lint.
./gradlew :app:lintDebug

# Compile-only fast check.
./gradlew :app:compileDebugKotlin --stacktrace

# Go-side vet + format.
go vet ./mobile/...
gofmt -l mobile/
```

Instrumented tests (`./gradlew :app:connectedDebugAndroidTest`)
require a connected emulator or device; CI does not run them.

## Conventions

### Kotlin

- Style: `kotlin.code.style=official` (see
  `android/gradle.properties`).
- ViewModels: `@HiltViewModel` + constructor injection. See
  `ui/profiles/ProfilesViewModel.kt` as the canonical example.
- Singletons: `object` declarations for cross-cutting state
  (`VpnManager`, `ConfigGenerator`, `GlobalSettingsStore`).
- Error handling: `runCatching { ... }.onFailure { ... }` for
  non-fatal failures; `try { ... } catch (_: Exception) {}` only
  for true background noise (e.g. closing sockets during shutdown).
- Coroutines: `CoroutineScope(SupervisorJob() + Dispatchers.X)`.
  Use `withContext(Dispatchers.IO)` for thread hops; avoid nested
  `launch` blocks inside a coroutine. Guard with `isActive` after
  suspending operations.
- Logging: `android.util.Log` for system logcat; `VpnManager.appendLog`
  for user-visible log lines (shown in the Logs screen, with a
  2000-line ring buffer). **Never log credentials** —
  `ProfileEntity.socksPass`, `tunnelKey`, `scriptKeysText` (and their
  JSON-serialized forms in `ConfigGenerator.exportProfileJson`) must
  not appear in logs.

### Go (in `mobile/`)

- `gofmt`-clean; `go vet ./mobile/...` returns no findings.
- Exported functions PascalCase (gomobile convention). Logger uses
  `log.Printf("[prefix] ...")` with brackets, e.g. `[TUN-API]`,
  `[client]`, `[socks]`.
- Mutex discipline: `mu` guards app state (`running`, `tunActive`,
  `tunBridgeRunning`, `cancelFn`, `socksLn`, `clientDone`). `engineMu`
  guards the tun2socks engine Start/Stop. Take them in the same
  order every time to avoid deadlocks.
- Errors wrapped with `fmt.Errorf("...: %w", err)`.

### Build / CI

- Commit style: `type(scope): subject` (conventional commits).
  Examples from `git log`: `fix(android): ...`,
  `feat(android): ...`, `perf(android): ...`, `style(ui): ...`,
  `chore(mobile): ...`, `docs(readme): ...`, `ci(android): ...`.
- Don't push or open PRs unless the operator instructed it.
- Don't commit secrets. Signing keys live in
  `$ANDROID_KEYSTORE_PATH` env var or in CI secrets.

## Things that are easy to get wrong

- **Credentials in logs.** Before adding any
  `VpnManager.appendLog(...)` or `Log.d(...)` call that includes
  profile data, redact `socksUser`, `socksPass`, `tunnelKey`, and
  `scriptKeysText`. The exported JSON config object contains all
  four.
- **`fallbackToDestructiveMigration`.** Don't add new Room schema
  versions without a real `Migration` entry in
  `ProfileMigrations.ALL` (see `data/local/ProfileMigrations.kt`).
  A missing migration will crash the app at launch for any user
  whose DB schema version is lower than the new one.
- **`engine.Stop()` panics.** The `recover()` blocks in
  `mobile.go`'s `StopTun`/`StopTunBridge` are load-bearing — they
  mask a tun2socks panic on stop that previously SIGSEGV'd the app
  (commit `41c3eef`). Don't remove them.
- **`network_security_config.xml` allows cleartext globally.**
  Tightening this is on the roadmap; don't add new `http://`
  fetches to production paths without revisiting this config.
- **Go mobile rebuild.** After any `mobile/*.go` change, run
  `bash ./android/build_go_mobile.sh` *before* `./gradlew :app:assembleDebug`
  — the Gradle build loads `android/app/libs/gooserelayvpn.aar` as a
  file dependency and will silently test against a stale AAR if the
  AAR isn't refreshed.

## Running the plans in `plans/`

The `plans/` directory contains self-contained implementation plans.
Each plan's filename is `NNN-short-slug.md`; `plans/README.md` is the
index (priority order, dependencies, status). Read the full plan
before starting, honor the STOP conditions, update the status row in
`plans/README.md` when done. Don't improvise when reality doesn't
match the plan — report back to the operator.

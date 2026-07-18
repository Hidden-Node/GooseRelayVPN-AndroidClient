# GooseRelayVPN Android Client
🌐 **[فارسی](README_FA.md)** 

[![Version](https://img.shields.io/badge/version-1.7.1-brightgreen)]()
[![Go Version](https://img.shields.io/badge/go-1.25.0-00ADD8)]()
[![Kotlin](https://img.shields.io/badge/kotlin-2.1.0-7F52FF)]()

Android client for GooseRelayVPN that runs the GooseRelay core through a Go mobile bridge and provides a complete Android UI for VPN lifecycle, profiles, logs, and settings.

- Upstream core project: https://github.com/kianmhz/GooseRelayVPN
- This repository: Android-focused client implementation

## What This App Does

This app creates a local SOCKS5 endpoint on Android and tunnels TCP traffic through the GooseRelay architecture:

1. Local app/browser traffic → SOCKS5
2. GooseRelay encrypted framing (AES-256-GCM key from your profile)
3. HTTPS path through Google-facing endpoints (Apps Script flow)
4. Your VPS exit server handles outbound target connections

The app wraps this flow in Android `VpnService` so selected/full traffic can be routed through the tunnel.

## App Screenshots

<table>
  <tr>
    <td align="center"><strong>Home</strong></td>
    <td align="center"><strong>Profiles</strong></td>
    <td align="center"><strong>Settings</strong></td>
    <td align="center"><strong>Logs</strong></td>
  </tr>
  <tr>
    <td><img src="screenshots/home.png" alt="Home Screen" width="300"/></td>
    <td><img src="screenshots/profiles.png" alt="Profiles Manager" width="300"/></td>
    <td><img src="screenshots/settings.png" alt="Settings Screen" width="300"/></td>
    <td><img src="screenshots/logs.png" alt="Logs" width="300"/></td>
  </tr>
</table>

<table>
  <tr>
    <td align="center"><strong>Profile Import</strong></td>
    <td align="center"><strong>Profile Sharing</strong></td>
    <td align="center"><strong>Settings Sharing</strong></td>
  </tr>
  <tr>
    <td><img src="screenshots/import-profile.png" alt="Import Profile" width="300"/></td>
    <td><img src="screenshots/share-profile.png" alt="Share Profile" width="300"/></td>
    <td><img src="screenshots/import&share-settings.png" alt="Share Settings" width="300"/></td>
  </tr>
</table>

## Key Features

### VPN & Tunneling
- 🔐 Android VPN integration (`VpnService` + tun2socks)
- 🔌 SOCKS5 proxy endpoint (configurable host:port)
- 🛡️ AES-256-GCM encryption with per-profile keys
- 📍 Domain fronting through Google infrastructure
- 🎯 Split tunneling with package selection

### Profile Management (v1.7.1+)
- 📋 **Remote profile subscriptions** - Sync profiles from remote HTTPS URLs
- 🔗 **goose-relay:// protocol** - Share profiles via Base64-encoded URI scheme
- 📤 **JSON import/export** - Full profile backup and restore
- 📋 **Clipboard import/export** - Quick copy-paste profile sharing
- 🔄 **Remote updates** - One-click refresh from remote source
- ⚙️ **Auto-save** - Changes saved automatically to local database

### Configuration
- **Multiple deployment IDs** - Support for multiple Google Apps Script deployments
- **Custom SOCKS settings** - Host, port, and optional auth (username/password)
- **SNI list** - Customizable Server Name Indication hostnames
- **Coalescing** - Fine-tune tunnel packet coalescing behavior
- **Idle slots** - Control idle slot distribution per bucket

### User Interface
- 🏠 **Home tab** - Real-time connection status with telemetry cards
- 📊 **Live logs** - Core and app-level diagnostic logs
- ⚙️ **Global settings** - Internet sharing, DNS, connection mode
- 🌍 **Settings tab** - Profile-specific configuration
- 🎨 Material Design 3 with dark mode support

### System Integration
- 📱 **Internet sharing** - Share VPN via SOCKS/HTTP proxy (LAN)
- 🔒 **Split tunneling** - Include/exclude specific apps from VPN
- 🌐 **Fake DNS** - Local DNS interception and resolution
- 🔐 **Allow LAN** - Route local network traffic outside VPN
- 🔋 **Low overhead** - Efficient Go-based core

## Configuration Model (Profile)

Profile fields are aligned with GooseRelay client config. Create profiles manually or import JSON:

```json
{
  "name": "My VPN Profile",
  "debug_timing": false,
  "socks_host": "127.0.0.1",
  "socks_port": 1080,
  "socks_user": "",
  "socks_pass": "",
  "google_host": "216.239.38.120",
  "sni": [
    "www.google.com",
    "mail.google.com",
    "accounts.google.com"
  ],
  "script_keys": [
    "REPLACE_WITH_DEPLOYMENT_ID",
    "OPTIONAL_SECOND_DEPLOYMENT_ID|account@example.com"
  ],
  "tunnel_key": "REPLACE_WITH_OUTPUT_OF_scripts_gen-key.sh",
  "coalesce_step_ms": 0,
  "idle_slots_per_bucket": 2
}
```

### Profile Fields

| Field | Type | Description |
|-------|------|-------------|
| `name` | string | Profile display name |
| `debug_timing` | boolean | Enable timing debug logs |
| `socks_host` | string | SOCKS5 server host (usually `127.0.0.1`) |
| `socks_port` | integer | SOCKS5 server port (1-65535) |
| `socks_user` | string | SOCKS5 username (optional, paired with password) |
| `socks_pass` | string | SOCKS5 password (optional, paired with username) |
| `google_host` | string | Google IP for domain fronting |
| `sni` | array | SNI hostnames for TLS |
| `script_keys` | array/string | Google Apps Script deployment ID(s). Format: `ID` or `ID\|account` |
| `tunnel_key` | string | Tunnel encryption key (server-side match required) |
| `coalesce_step_ms` | integer | Packet coalescing timeout (milliseconds) |
| `idle_slots_per_bucket` | integer | Idle slots per bucket (1-3) |

### Profile Import/Export Methods (v1.7.1+)

#### 1. Manual Profile
- Tap the `+` button → Select "Add Manual"
- Fill in fields directly in the form

#### 2. JSON File
- Tap the `+` button → Select "From JSON"
- Choose a `.json` file from your device
- Profile fields are auto-populated

#### 3. Clipboard (Recommended) — **NEW**
- Copy JSON or `goose-relay://` protocol to clipboard
- Tap the `+` button → Select "From Clipboard"
- Profile is created instantly

#### 4. Profile Sharing (goose-relay:// Protocol) — **NEW**
- Open a profile → Tap **Share** icon
- Select "to Clipboard" to copy `goose-relay://` URI
- Share via messaging, QR code, or link
- Recipients paste into clipboard and tap "From Clipboard"

### Remote Profile Subscriptions (v1.7.1+) — **NEW**

#### Setting Up Remote Sync
1. **Edit Profile** and add Remote URL: `https://your-server.com/profiles/my-profile.json`
2. Profile will fetch and auto-update from this URL on each refresh
3. **Tap Refresh** (↻) icon in Profiles toolbar to manually sync
4. Changes save locally automatically

#### Remote URL Format
```
https://example.com/path/to/profile.json
```

Supported endpoints:
- Any HTTPS server hosting profile JSON
- Optional HTTP Basic Auth: `https://user:pass@example.com/profile.json`
- Self-signed certs: Certificate validation required (add to system trust store)

#### Auto-Update Behavior
- Profiles with remote URLs fetch on **manual refresh** only (not automatic)
- All fields except `remoteUrl` are updated from remote
- Local changes are preserved if remote URL is not set
- On update failure, error message displayed in snackbar

## Default Split Tunneling Apps

By default, these apps bypass the VPN (included in split tunnel list):

- Instagram (`com.instagram.android`)
- Telegram (`org.telegram.messenger`)
- WhatsApp (`com.whatsapp`)
- YouTube (`com.google.android.youtube`)
- Chrome (`com.android.chrome`)
- Gmail (`com.google.android.gm`)
- Google Play Store (`com.android.vending`)
- Google Search (`com.google.android.googlequicksearchbox`)
- Twitter/X (`com.twitter.android`)
- OpenAI ChatGPT (`com.openai.chatgpt`)

Modify in **Global Settings** → **Split Tunneling** → Edit package list.

## Global Settings

### Connection Mode
- **VPN**: Full VPN routing (default)
- **SOCKS**: Direct SOCKS proxy (app-by-app)

### Tunneling
- **Split Tunneling**: Enable per-app VPN selection (default: enabled)
- **Mode**: Include selected apps or exclude selected apps
- **Allow LAN**: Route local network traffic outside VPN
- **Fake DNS**: Intercept DNS queries locally (default: enabled)

### Internet Sharing
- **Share VPN via Network**: Enable/disable
- **SOCKS Port**: Proxy port for LAN devices (default: 8090)
- **HTTP Port**: HTTP proxy port (default: 8091)
- **Auth**: Optional username/password for proxies

### Advanced
- **Custom DNS Servers**: Comma-separated DNS IP addresses
- **Auto-save**: Changes persist automatically (default: enabled in v1.7.1+)

### Clipboard Export/Import (v1.7.1+) — **NEW**
- Tap **⋮** (More) menu in Settings toolbar
- **Export to Clipboard** — Copy all settings as JSON
- **Import from Clipboard** — Paste previously exported settings

## Upstream Setup Flow (Required)

You still need upstream infrastructure prepared first:

1. **Prepare VPS**
   - Deploy VPS with public IP
   - Run `goose-server` binary
   - Note the VPS IP/domain

2. **Deploy Google Apps Script**
   - Copy `apps_script/Code.gs` from upstream repo
   - Create Google Apps Script project
   - Paste code and deploy as web app
   - Note the **Deployment ID**

3. **Generate Encryption Key**
   - Run `scripts/gen-key.sh`
   - Output is your `tunnel_key`

4. **Add Profile to App**
   - Create new profile in app
   - Set `script_keys` to deployment ID(s)
   - Set `tunnel_key` to generated key
   - Set `google_host` (usually 216.239.38.120)

5. **Connect & Verify**
   - Tap **Connect** on Home
   - Check **Logs** for any errors
   - Verify traffic flows through VPN

For complete infrastructure details, see upstream docs:
- https://github.com/kianmhz/GooseRelayVPN

## Android Build (Local Development)

### Requirements
- Android Studio 2024.1+
- JDK 17+
- Go 1.25.0+ (was 1.22, upgraded in v1.7.1)
- Android SDK API 36+
- Android NDK (latest)

### Build Go Mobile Bridge

```bash
cd android
bash ../build_go_mobile.sh
```

This creates AAR library with GooseRelay core compiled for all architectures.

### Build Debug APK

```bash
cd android
./gradlew :app:assembleDebug
```

Output: `app/build/outputs/apk/debug/GooseRelayVPN.apk`

### Build Release APK

Requires signing configuration in `local.properties`:

```properties
ANDROID_KEYSTORE_PATH=/path/to/keystore.jks
ANDROID_KEYSTORE_PASSWORD=keystore_password
ANDROID_KEY_ALIAS=key_alias
ANDROID_KEY_PASSWORD=key_password
```

Then:

```bash
cd android
./gradlew :app:bundleRelease
```

Output: `app/build/outputs/bundle/release/app-release.aab`

## Release & CI/CD

### GitHub Workflows

**`.github/workflows/android-ci.yml`**
- Runs on every commit
- Builds debug APK
- Runs unit tests
- Checks code quality

**`.github/workflows/release.yml`**
- Triggered on version tag (v*.*.*)
- Builds signed release APK/AAB
- Creates GitHub Release with artifacts
- Requires signing secrets

**`.github/workflows/release-manual.yml`**
- Manual trigger for releases
- Same as release.yml but on-demand

### Secrets Required

Configure these in GitHub repository settings:

- `ANDROID_KEYSTORE_BASE64` — Base64-encoded keystore file
- `ANDROID_KEYSTORE_PASSWORD` — Keystore password
- `ANDROID_KEY_ALIAS` — Key alias in keystore
- `ANDROID_KEY_PASSWORD` — Key password

### Build Artifacts

Release produces:
- `GooseRelayVPN.apk` — Universal APK (all architectures)
- `GooseRelayVPN-arm64-v8a.apk` — ARM64 only
- `GooseRelayVPN-armeabi-v7a.apk` — ARMv7 only
- `GooseRelayVPN-x86_64.apk` — x86_64 only
- `app-release.aab` — Google Play App Bundle

## Troubleshooting

### Connection Issues

**SOCKS Port Busy**
- Disconnect VPN, wait 3 seconds, reconnect
- Check no other app uses port 1080 (or configured port)
- Kill app via settings and restart

**Connection Stays "Preparing"**
- Verify `script_keys` (deployment ID) is correct
- Verify `tunnel_key` matches server-side key
- Check **Logs** tab for core error messages
- Ensure VPS is running and reachable

**No Traffic Flows**
- Verify VPN permission is granted
- Check split tunneling app selection
- Ensure profile is selected and connected
- Review **Logs** for network errors

### Performance Issues

**High Battery/Data Usage**
- Disable debug logging (set `debug_timing: false`)
- Check split tunnel configuration
- Monitor active connections in **Logs**

**Slow Connections**
- Try different `google_host` values
- Adjust `coalesce_step_ms` (higher = more batch, more latency)
- Test on different networks (WiFi vs mobile)

### Build Issues

**Gradle Error: "Failed to install gomobile"**
- Check Go version: `go version` (need 1.25.0+)
- Delete `$GOPATH/pkg/mod/golang.org/x/mobile` and retry

**NDK Not Found**
- Install via Android Studio: Settings → SDK Manager → SDK Tools
- Set `ANDROID_NDK` environment variable

## Architecture Overview

```
┌─────────────────────────────────────────────┐
│   Android UI Layer (Compose)                │
│  ┌──────────────────────────────────────┐   │
│  │ Home │ Profiles │ Settings │ Logs    │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────┐
│   ViewModels & Repository Layer             │
│  ┌──────────────────────────────────────┐   │
│  │ Room Database │ DataStore │ Hilt DI │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────┐
│   VPN Service & Go Core                     │
│  ┌──────────────────────────────────────┐   │
│  │ tun2socks │ GooseRelay Core (Go)     │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
            ↓
┌─────────────────────────────────────────────┐
│   Network Layer                             │
│  ┌──────────────────────────────────────┐   │
│  │ SOCKS5 → Google Apps Script → VPS   │   │
│  └──────────────────────────────────────┘   │
└─────────────────────────────────────────────┘
```

## Version History

### v1.7.1 (Current) — July 2026
- ✅ **Remote profile subscriptions** with manual refresh
- ✅ **goose-relay:// protocol** for URL-based profile sharing
- ✅ **Clipboard import/export** for profiles
- ✅ **Global settings clipboard** export/import
- ✅ **Auto-save** for profile and global settings changes
- ✅ **Dropdown menus** for profile management (+ button, share)
- ✅ **Default split tunneling apps** (Instagram, Telegram, WhatsApp, YouTube, Chrome, Gmail, Play Store, etc.)
- ✅ Updated to **Gradle 8.13**, **Android Gradle Plugin 8.13.0**
- ✅ Updated to **Go 1.25.0** with latest dependencies
- ✅ JVM memory increased (Xmx4096m)
- ✅ Fixed SOCKS field defaults in JSON export
- ✅ Improved error handling for imports

### v1.0.0+ — Earlier Releases
- VPN service with tun2socks
- Profile management
- Live logs and diagnostics
- Split tunneling
- Internet sharing

## Tech Stack

### Android
- **Language**: Kotlin 2.1.0
- **Framework**: Jetpack Compose
- **Architecture**: MVVM with Clean Architecture
- **Database**: Room ORM
- **DI**: Hilt
- **VPN**: Android VpnService API, tun2socks
- **Build**: Gradle 8.13, Android Gradle Plugin 8.13.0

### Go Core
- **Version**: 1.25.0 (upgraded from 1.23.1)
- **Crypto**: AES-256-GCM (golang.org/x/crypto)
- **Networking**: golang.org/x/net 0.52.0, tun2socks
- **Compression**: klauspost/compress (Brotli, Gzip)
- **Logging**: Uber zap

### Key Dependencies

**Go:**
- `github.com/things-go/go-socks5` — SOCKS5 server
- `github.com/xjasonlyu/tun2socks/v2` — TUN device handler
- `golang.org/x/net v0.52.0` — Networking utilities
- `golang.org/x/mobile v0.0.0-20260312152759-81488f6aeb60` — Go mobile binding
- `golang.org/x/crypto v0.49.0` — Cryptography

**Android:**
- Jetpack Compose, Navigation, Hilt, Room
- Datastore Proto, Gson, Coil (images)

See `go.mod`, `go.sum`, and `android/build.gradle.kts` for complete lists.

## Credits

1. **Main GooseRelay Project**
   - https://github.com/Kianmhz/GooseRelayVPN

2. **Inspiration**
   - https://github.com/masterking32/MasterHttpRelayVPN

3. **Community**
   - Go, Kotlin, Jetpack Compose teams
   - tun2socks, SOCKS5, and networking library authors

## License

See [LICENSE](LICENSE) file for details.

## Support & Contributing

- **Issues**: Report bugs on [GitHub Issues](https://github.com/ArashAfkandeh/GooseRelayVPN-AndroidClient/issues)
- **Discussions**: Join [GitHub Discussions](https://github.com/ArashAfkandeh/GooseRelayVPN-AndroidClient/discussions)
- **Contributing**: PRs welcome! 
- **Upstream**: Questions about core protocol → [GooseRelayVPN](https://github.com/kianmhz/GooseRelayVPN)

## Disclaimer

This tool is for educational and authorized use only. Users are responsible for:
- Compliance with local laws and regulations
- Proper authorization from network administrators
- Ethical use of VPN technology
- Respecting terms of service of infrastructure providers

Developers are not liable for misuse or damages.

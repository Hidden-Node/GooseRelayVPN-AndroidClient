This directory is where the gomobile-generated `.aar` file goes.
Run `android/build_go_mobile.sh` (Linux/macOS) or `android/build_go_mobile.bat` (Windows).
Expected output: `gooserelayvpn.aar`.

The AAR is the gomobile binding of `mobile/` (the Go core + the gVisor TUN bridge). It is a **build artifact** and is never committed to git.

Rebuild commands:
- Windows: `android\build_go_mobile.bat` (or via Git Bash: `& "C:\Program Files\Git\bin\bash.exe" ./android/build_go_mobile.sh`)
- POSIX / CI: `bash ./android/build_go_mobile.sh`

The Gradle task `./gradlew :app:verifyMobileAar` (hooked into `preBuild`) fails the build when the AAR is missing, invalid, or does not match the current engine (e.g. contains legacy tun2socks/FakeDNS symbols or lacks current gVisor symbols).

The AAR must be rebuilt after **any** change under `mobile/`.


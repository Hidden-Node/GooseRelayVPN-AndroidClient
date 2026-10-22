@echo off
REM ============================================================================
REM GooseRelayVPN - Build Go Mobile Library for Android (Windows)
REM Prerequisites: Go 1.25+, Android NDK, ANDROID_HOME / ANDROID_NDK_HOME
REM ============================================================================

set /p MOBILE_TOOLS_VERSION=<"%~dp0gomobile.version"
echo ===================================
echo GooseRelayVPN - Android Build Script
echo ===================================
echo.

REM Check go
where go >nul 2>&1
if errorlevel 1 (
    echo ERROR: Go is not installed or not in PATH
    echo Download from: https://go.dev/dl/
    pause
    exit /b 1
)

cd /d "%~dp0.."

git diff --quiet HEAD -- go.mod go.sum
if errorlevel 1 (
    echo.
    echo ERROR: go.mod/go.sum already differ from HEAD ^(staged or unstaged^).
    echo Refusing to build because android builds must not modify shared Go module files.
    git diff HEAD -- go.mod go.sum
    pause
    exit /b 1
)

REM Install pinned gomobile toolchain - each install gets its own error test,
REM because `if errorlevel 1` only sees the LAST command.
go install golang.org/x/mobile/cmd/gomobile@%MOBILE_TOOLS_VERSION%
if errorlevel 1 (
    echo ERROR: Failed to install gomobile
    pause
    exit /b 1
)
go install golang.org/x/mobile/cmd/gobind@%MOBILE_TOOLS_VERSION%
if errorlevel 1 (
    echo ERROR: Failed to install gobind
    pause
    exit /b 1
)

REM gomobile lands in %GOPATH%\bin - make sure it is on PATH for this session.
REM %GOPATH% may be unset; ask the go tool instead of trusting the env var.
for /f "delims=" %%i in ('go env GOPATH') do set "GO_BIN=%%i\bin"
if not defined GO_BIN (
    echo ERROR: Could not resolve GOPATH
    pause
    exit /b 1
)
set "PATH=%GO_BIN%;%PATH%"

gomobile init
if errorlevel 1 (
    echo ERROR: gomobile init failed
    pause
    exit /b 1
)

echo.
echo [1/2] Building Go mobile library...
echo.

set "SOURCE_ROOT=%CD%"
set "BUILD_ROOT=%TEMP%\gooserelayvpn-gomobile-%RANDOM%%RANDOM%"
mkdir "%BUILD_ROOT%"
if errorlevel 1 (
    echo ERROR: Failed to create temporary build directory
    pause
    exit /b 1
)

REM /XF keeps gitignored secrets out of %TEMP%; the Go build never needs them.
robocopy "%SOURCE_ROOT%" "%BUILD_ROOT%" /MIR /XD .git android\.gradle android\app\build android\build android\app\libs /XF client_config.json server_config.json *.token >nul
if errorlevel 8 (
    echo ERROR: Failed to copy source tree for isolated gomobile build
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

cd /d "%BUILD_ROOT%"

REM Resolve gomobile's bind dependency only in this temporary build tree.
go get golang.org/x/mobile@%MOBILE_TOOLS_VERSION%
if errorlevel 1 (
    echo ERROR: Failed to prepare temporary gomobile module dependencies
    cd /d "%SOURCE_ROOT%"
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

go mod download
if errorlevel 1 (
    echo ERROR: Failed to download temporary gomobile module dependencies
    cd /d "%SOURCE_ROOT%"
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

if not exist "%SOURCE_ROOT%\android\app\libs" mkdir "%SOURCE_ROOT%\android\app\libs"

REM The hints are QUOTED: inside a parenthesised block, an unquoted `)` in the
REM expanded value (a user name with parens) would close the block early - the
REM exact defect this script fixes. Quotes also make <version> literal.
gomobile bind -v -target=android/arm64,android/arm,android/amd64,android/386 -androidapi 21 -o "%SOURCE_ROOT%\android\app\libs\gooserelayvpn.aar" ./mobile/

if errorlevel 1 (
    echo.
    echo ERROR: gomobile bind failed!
    echo Make sure ANDROID_HOME and ANDROID_NDK_HOME are set correctly.
    echo.
    echo ANDROID_HOME should point to: "%LOCALAPPDATA%\Android\Sdk"
    echo ANDROID_NDK_HOME should point to: "%LOCALAPPDATA%\Android\Sdk\ndk\<version>"
    cd /d "%SOURCE_ROOT%"
    rmdir /s /q "%BUILD_ROOT%"
    pause
    exit /b 1
)

cd /d "%SOURCE_ROOT%"
rmdir /s /q "%BUILD_ROOT%"

git diff --quiet -- go.mod go.sum
if errorlevel 1 (
    echo.
    echo ERROR: gomobile build modified shared Go module files.
    echo Refusing to continue because android builds must not change go.mod/go.sum.
    git diff -- go.mod go.sum
    pause
    exit /b 1
)

echo.
echo [2/2] Go mobile library built successfully!
echo Output: android/app/libs/gooserelayvpn.aar
echo.
echo You can now open the android/ folder in Android Studio and build the APK.
echo Or run: cd android ^&^& gradlew assembleDebug
echo.
pause

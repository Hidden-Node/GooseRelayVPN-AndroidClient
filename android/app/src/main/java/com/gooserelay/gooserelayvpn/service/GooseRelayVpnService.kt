package com.gooserelay.gooserelayvpn.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.net.wifi.WifiManager
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.gooserelay.gooserelayvpn.App
import com.gooserelay.gooserelayvpn.MainActivity
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.AppDatabase
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import com.gooserelay.gooserelayvpn.util.GlobalSettingsStore
import com.gooserelay.gooserelayvpn.util.VpnManager
import kotlinx.coroutines.*
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext

class GooseRelayVpnService : VpnService() {

    companion object {
        const val ACTION_CONNECT = "com.gooserelay.gooserelayvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.gooserelay.gooserelayvpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val TAG = "GooseRelayVPN"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_SOCKS_PORT = 1080
        private const val SOCKS_STARTUP_TIMEOUT_MS = 30 * 60 * 1000L
        private const val SOCKS_POLL_INTERVAL_MS = 500L

        // Base companions that many apps need for network functionality.
        // WebView is used by in-app browsers, ads, login flows, etc.
        // GMS provides play services, auth, and connectivity checks.
        private val BASE_COMPANION_PACKAGES = setOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.google.android.gms",
            "com.google.android.gsf",
            "com.google.android.captiveportallogin"
        )

        // Additional companions needed specifically for browsers.
        private val BROWSER_COMPANION_PACKAGES = setOf(
            "com.android.chrome"           // system Chrome on some OEMs
        )

    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var connectJob: Job? = null
    private var vpnInterface: ParcelFileDescriptor? = null
    private var goClientJob: Job? = null
    private var httpProxyJob: Job? = null
    private var sharingSocksJob: Job? = null
    private var sharingSocksServer: java.net.ServerSocket? = null
    private var sharingHttpServer: java.net.ServerSocket? = null
    private var logTailJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var keepaliveJob: Job? = null
    private var isStopping = false
    @Volatile
    private var tunBridgeActive = false
    @Volatile
    private var socksAuthWarningShown = false
    @Volatile
    private var sessionBusyWarningShown = false
    @Volatile
    private var activeLocalSocksPort: Int = DEFAULT_SOCKS_PORT

    private var networkCallback: android.net.ConnectivityManager.NetworkCallback? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "VPN Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CONNECT -> {
                val profileId = intent.getLongExtra(EXTRA_PROFILE_ID, -1)
                if (profileId > 0) {
                    startVpn(profileId)
                }
            }
            ACTION_DISCONNECT -> {
                stopVpn()
            }
        }
        return START_STICKY
    }

    private fun startVpn(profileId: Long) {
        connectJob?.cancel()
        connectJob = serviceScope.launch {
            try {
                VpnManager.updateState(VpnManager.VpnState.CONNECTING)
                VpnManager.clearError()
                socksAuthWarningShown = false
                sessionBusyWarningShown = false

                // Show foreground notification
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_connecting)))
                acquireWakeLock()

                // Load profile from DB
                val db = AppDatabase.getInstance(this@GooseRelayVpnService)
                val profile = db.profileDao().getProfileById(profileId)
                    ?: throw IllegalStateException("Profile not found")
                val socksPort = profile.socksPort.takeIf { it in 1..65535 } ?: DEFAULT_SOCKS_PORT
                activeLocalSocksPort = socksPort
                val globalSettings = GlobalSettingsStore.load(this@GooseRelayVpnService)
                val proxyMode = globalSettings.connectionMode.equals("PROXY", ignoreCase = true)

                VpnManager.appendLog("Loading profile: ${profile.name}")
                VpnManager.appendLog("Global Settings Loaded - connectionMode: ${globalSettings.connectionMode}, customDnsServers: '${globalSettings.customDnsServers}', fakeDnsEnabled: ${globalSettings.fakeDnsEnabled}")

                // Check if Go core is still running from previous session
                ensureGoCoreStopped()

                ensureSocksPortAvailable(socksPort)

                // Generate config files
                val configDir = File(filesDir, "config")
                configDir.mkdirs()

                val configFile = File(configDir, "client_config.json")
                val generatedConfig = ConfigGenerator.generateConfig(profile)
                configFile.writeText(generatedConfig)

                VpnManager.appendLog("Config written to: ${configFile.absolutePath}")
                VpnManager.appendLog("DEBUG: Generated config: $generatedConfig")
                VpnManager.appendLog("Starting Go core...")

                // Start Go client in background thread
                val logFile = File(cacheDir, "vpn.log")
                if (!logFile.exists()) {
                    logFile.createNewFile()
                } else {
                    logFile.writeText("")
                }

                logTailJob?.cancel()
                logTailJob = launch(Dispatchers.IO) {
                    tailLogFile(logFile)
                }

                goClientJob = launch(Dispatchers.IO) {
                    try {
                        // Call the Go mobile wrapper
                        mobile.Mobile.startClient(
                            configFile.absolutePath,
                            logFile.absolutePath
                        )
                    } catch (_: CancellationException) {
                        // Normal shutdown — coroutine was cancelled during disconnect.
                        VpnManager.appendLog("Go core stopped (coroutine cancelled)")
                    } catch (e: Exception) {
                        // Only log real errors, not context cancellation from Go.
                        val msg = e.message ?: ""
                        val isNormalShutdown = msg.contains("context canceled", ignoreCase = true) ||
                                msg.contains("use of closed network connection", ignoreCase = true)
                                
                        if (!isNormalShutdown) {
                            Log.e(TAG, "Go core error", e)
                            VpnManager.appendLog("Go core error: $msg")
                            runCatching {
                                VpnManager.setError("Go core error: $msg")
                            }
                        }
                    }
                }

                // Wait until SOCKS5 is actually listening.
                waitForSocksProxyReady(
                    host = "127.0.0.1",
                    port = socksPort,
                    timeoutMs = SOCKS_STARTUP_TIMEOUT_MS
                )
                VpnManager.appendLog("SOCKS5 proxy is ready on 127.0.0.1:$socksPort")

                // Start Internet Sharing proxies if enabled
                if (globalSettings.internetSharingEnabled) {
                    val socksPort = globalSettings.internetSharingSocksPort
                    val httpPort = globalSettings.internetSharingHttpPort
                    val user = globalSettings.internetSharingUser
                    val pass = globalSettings.internetSharingPass
                    startInternetSharing(socksPort, httpPort, user, pass)
                }

                if (proxyMode) {
                    VpnManager.appendLog("Proxy mode active: skipping Android VpnService TUN setup")
                    acquireWifiLock()
                    VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                    VpnManager.startTrafficMonitor(this@GooseRelayVpnService)
                    val notification = buildNotification("Proxy mode active on port $socksPort")
                    val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                    manager.notify(NOTIFICATION_ID, notification)
                    startKeepalive()
                    return@launch
                }

                // DNS configuration: use custom DNS servers if provided, otherwise use defaults.
                // For remote DNS resolution (to bypass filtered DNS in Iran), configure custom
                // DNS servers that are accessible through the VPN tunnel (e.g., your VPS IP or
                // public DNS servers that will be routed through the tunnel).
                val vpnDnsServers = if (globalSettings.customDnsServers.isNotBlank()) {
                    globalSettings.customDnsServers
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .also { servers ->
                            VpnManager.appendLog("Using custom DNS servers: ${servers.joinToString()}")
                        }
                } else if (globalSettings.fakeDnsEnabled) {
                    // In fake DNS mode, point to TUN bridge DNS
                    listOf("172.19.0.2").also {
                        VpnManager.appendLog("Using Go TUN bridge DNS: 172.19.0.2")
                    }
                } else {
                    // Default: multiple public resolvers to reduce startup stalls on filtered networks.
                    // Note: In TUN mode, DNS resolution happens on the client side before traffic
                    // enters the tunnel. For true remote DNS resolution, either:
                    // 1. Use Proxy mode (socks5h clients handle DNS remotely), or
                    // 2. Set custom DNS to your VPS IP (if running a DNS server there), or
                    // 3. Use a fake DNS approach (advanced, requires additional setup)
                    listOf(
                        "1.1.1.1",
                        "8.8.8.8",
                        "9.9.9.9",
                        "94.140.14.14"
                    ).also { VpnManager.appendLog("Using default DNS servers") }
                }

                val builder = Builder()
                    .setSession(getString(R.string.app_name))
                    .setMtu(1500)
                    .setBlocking(false)
                    .setUnderlyingNetworks(null)
                
                if (globalSettings.fakeDnsEnabled) {
                    builder.addAddress("172.19.0.1", 30)
                } else {
                    builder.addAddress("10.0.0.2", 32)
                }
                
                builder.addRoute("0.0.0.0", 0)

                // Prevent IPv6 DNS leaks: if we don't route IPv6 and provide an IPv6 DNS,
                // Android may leak DNS requests to the cellular network's IPv6 DNS server,
                // resulting in hijacked IPs like 10.10.34.36 from the ISP.
                try {
                    builder.addAddress("fc00::1", 128)
                    builder.addRoute("::", 0)
                } catch (e: Exception) {
                    VpnManager.appendLog("IPv6 routing skipped: ${e.message}")
                }

                vpnDnsServers.forEach { builder.addDnsServer(it) }
                VpnManager.appendLog("VPN DNS servers: ${vpnDnsServers.joinToString()}")
                
                // In fake DNS mode, route fake IP range through VPN
                if (globalSettings.fakeDnsEnabled) {
                    builder.addRoute("198.18.0.0", 16)
                    VpnManager.appendLog("Added route for fake DNS range: 198.18.0.0/16")
                }

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                    val splitEnabled = globalSettings.splitTunnelingEnabled &&
                        globalSettings.splitPackagesCsv.isNotBlank()
                    if (splitEnabled) {
                        val userSelected = globalSettings.splitPackagesCsv
                            .split(",")
                            .map { it.trim() }
                            .filter { it.isNotEmpty() }
                            .toSet()

                        if (globalSettings.splitTunnelMode == com.gooserelay.gooserelayvpn.util.SplitTunnelMode.INCLUDE) {
                            val pm = packageManager
                            val appCompanions = mutableSetOf<String>()

                            (BASE_COMPANION_PACKAGES + BROWSER_COMPANION_PACKAGES).forEach { pkg ->
                                if (runCatching { pm.getApplicationInfo(pkg, 0) }.isSuccess) {
                                    appCompanions.add(pkg)
                                }
                            }

                            // Do NOT include our own packageName here.
                            val finalAllowed = userSelected + appCompanions

                            VpnManager.appendLog(
                                "Split tunnel (Include): ${userSelected.size} apps, " +
                                "${appCompanions.size} companions"
                            )

                            finalAllowed.forEach { pkg ->
                                try {
                                    builder.addAllowedApplication(pkg)
                                } catch (e: Exception) {
                                    VpnManager.appendLog("Split tunnel skip '$pkg': ${e.message}")
                                }
                            }
                        } else {
                            VpnManager.appendLog("Split tunnel (Exclude): Bypassing ${userSelected.size} apps")
                            try { builder.addDisallowedApplication(packageName) } catch (e: Exception) {}
                            userSelected.forEach { pkg ->
                                try {
                                    builder.addDisallowedApplication(pkg)
                                } catch (e: Exception) {
                                    VpnManager.appendLog("Split tunnel skip '$pkg': ${e.message}")
                                }
                            }
                        }
                    } else {
                        // Exclude app itself by default to avoid self-loop traffic.
                        builder.addDisallowedApplication(packageName)
                    }
                }

                vpnInterface = builder.establish()
                    ?: throw IllegalStateException("VPN interface could not be established. Check VPN permission.")

                VpnManager.appendLog("TUN interface established (fd=${vpnInterface!!.fd})")

                // Start TUN bridge (either Go TUN with DNS interception or standard tun2socks)
                if (globalSettings.fakeDnsEnabled) {
                    try {
                        VpnManager.appendLog("Starting Go TUN bridge with DNS interception...")
                        
                        // TUN module is included in mobile package
                        val tunClass = Class.forName("mobile.Mobile")
                        val startMethod = tunClass.getMethod(
                            "startTunBridge",
                            Long::class.java,
                            Long::class.java,
                            String::class.java,
                            String::class.java,
                            String::class.java,
                        )
                        
                        // Start TUN bridge: fd, mtu, socksAddr, socksUser, socksPass
                        // The Go core's SOCKS5 server enforces user/pass auth when
                        // both fields are non-empty; we forward them so the FakeDNS
                        // proxy can negotiate through it.
                        startMethod.invoke(
                            null,
                            vpnInterface!!.fd.toLong(),
                            1500L,
                            "127.0.0.1:$socksPort",
                            profile.socksUser,
                            profile.socksPass,
                        )
                        
                        tunBridgeActive = true
                        VpnManager.appendLog("Go TUN bridge started (DNS will be resolved remotely)")
                    } catch (e: Exception) {
                        VpnManager.appendLog("Failed to start Go TUN bridge: ${e.message}")
                        Log.e(TAG, "TUN bridge error", e)
                        throw e
                    }
                } else {
                    // Standard tun2socks bridge without DNS interception
                    VpnManager.appendLog("Starting tun2socks bridge: TUN fd -> socks5://127.0.0.1:$socksPort")
                    mobile.Mobile.startTun(vpnInterface!!.fd.toLong(), "127.0.0.1:$socksPort")
                }

                // Register Network Change detection to route TUN gracefully
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                networkCallback?.let { cm.unregisterNetworkCallback(it) }
                networkCallback = object : android.net.ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: android.net.Network) {
                        val caps = cm.getNetworkCapabilities(network)
                        if (caps == null || caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return
                        if (isStopping) return
                        
                        VpnManager.appendLog("Underlying network changed, updating VPN underlying network...")
                        setUnderlyingNetworks(arrayOf(network))
                    }
                }
                try {
                    val request = android.net.NetworkRequest.Builder()
                        .addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build()
                    cm.registerNetworkCallback(request, networkCallback!!)
                } catch (e: Exception) {
                    VpnManager.appendLog("Failed to register network callback: ${e.message}")
                }

                // Update state
                VpnManager.updateState(VpnManager.VpnState.CONNECTED)
                VpnManager.startTrafficMonitor(this@GooseRelayVpnService)
                VpnManager.appendLog("VPN connected successfully!")

                // Update notification
                val notification = buildNotification(getString(R.string.notification_connected))
                val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
                manager.notify(NOTIFICATION_ID, notification)

            } catch (e: CancellationException) {
                VpnManager.appendLog("Connection canceled")
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start VPN", e)
                VpnManager.appendLog("Error: ${e.message}")
                VpnManager.setError(e.message ?: "Unknown error")
                stopVpn()
            }
        }
    }

    private fun stopVpn() {
        if (isStopping) return
        isStopping = true
        
        VpnManager.updateState(VpnManager.VpnState.DISCONNECTING)
        
        // Use a separate scope so that serviceScope.cancel() in onDestroy()
        // does not kill this coroutine mid-cleanup.
        val stopScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        stopScope.launch {
            try {
                connectJob?.cancel()
                VpnManager.appendLog("Stopping VPN...")
                tunBridgeActive = false

                // Stop everything in Go layer via a single stopClient() call.
                // Go's StopClient() internally handles StopTun/StopTunBridge
                // with idempotent guards and panic recovery, so this is safe.
                val stopThread = Thread {
                    VpnManager.appendLog("Stopping Go core...")
                    runCatching {
                        mobile.Mobile.stopClient()
                    }.onFailure { e ->
                        VpnManager.appendLog("Go core stop error: ${e.message}")
                    }
                }
                stopThread.start()
                stopThread.join(5000L)
                if (stopThread.isAlive) {
                    VpnManager.appendLog("Go core stop timed out, proceeding anyway")
                } else {
                    VpnManager.appendLog("Go core stopped successfully")
                }

                // Close TUN fd AFTER Go core stops to avoid EBADF in goroutines.
                VpnManager.appendLog("Closing TUN interface...")
                val iface = vpnInterface
                vpnInterface = null
                runCatching { iface?.close() }

                // Cancel coroutines
                VpnManager.appendLog("Stopping Android session jobs...")
                goClientJob?.cancel()
                httpProxyJob?.cancel()
                sharingSocksJob?.cancel()
                keepaliveJob?.cancel()
                logTailJob?.cancel()

                networkCallback?.let {
                    runCatching {
                        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        cm.unregisterNetworkCallback(it)
                    }
                    networkCallback = null
                }

                // Close sharing servers
                runCatching { sharingSocksServer?.close() }
                sharingSocksServer = null
                runCatching { sharingHttpServer?.close() }
                sharingHttpServer = null

                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                VpnManager.appendLog("VPN disconnected")
                releaseWakeLock()

                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        stopForeground(STOP_FOREGROUND_REMOVE)
                    } else {
                        @Suppress("DEPRECATION")
                        stopForeground(true)
                    }
                }.onFailure {
                    Log.w(TAG, "Failed to stop foreground cleanly", it)
                }

                // Delay to allow UI to update before stopping service
                delay(500L)
                runCatching { stopSelf() }
            } catch (e: Exception) {
                Log.e(TAG, "Error in stopVpn", e)
                VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
                VpnManager.stopTrafficMonitor()
                runCatching { stopSelf() }
            }
            // NOTE: isStopping intentionally stays true until onDestroy() completes.
            // This prevents onDestroy() from double-closing already-freed resources.
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        return NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_vpn_key)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    override fun onDestroy() {
        // Normal path: stopVpn() already ran — Go layer guards make re-calls no-ops.
        // Force-kill path: stopVpn() was never called, so do full cleanup.
        if (!isStopping) {
            // stopClient() internally handles StopTun + StopTunBridge + cancel
            // with idempotent guards and panic recovery.
            keepaliveJob?.cancel()
            try { mobile.Mobile.stopClient() } catch (_: Exception) {}
            try {
                vpnInterface?.close()
            } catch (_: Exception) {}
            vpnInterface = null
        }
        networkCallback?.let {
            runCatching {
                val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                cm.unregisterNetworkCallback(it)
            }
            networkCallback = null
        }
        releaseWakeLock()
        releaseWifiLock()
        isStopping = false
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private suspend fun waitForSocksProxyReady(host: String, port: Int, timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            coroutineContext.ensureActive()

            val clientJob = goClientJob
            if (clientJob != null && clientJob.isCompleted && !mobile.Mobile.isRunning()) {
                throw IllegalStateException("Go core stopped before SOCKS5 became ready")
            }

            if (canConnect(host, port)) {
                return
            }
            delay(SOCKS_POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Timed out waiting for SOCKS5 listener on $host:$port")
    }

    private fun canConnect(host: String, port: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), 300)
                true
            }
        } catch (_: Exception) {
            false
        }
    }

    private suspend fun ensureSocksPortAvailable(port: Int) {
        if (!isLocalPortInUse(port)) return
        VpnManager.appendLog("SOCKS5 port $port is busy, attempting to free it...")

        runCatching {
            if (mobile.Mobile.isRunning()) {
                mobile.Mobile.stopClient()
            }
        }

        repeat(15) {
            delay(300L)
            if (!isLocalPortInUse(port)) {
                VpnManager.appendLog("SOCKS5 port $port released successfully")
                return
            }
            VpnManager.appendLog("SOCKS5 port $port still busy, retrying...")
        }

        throw IllegalStateException("SOCKS5 port $port is already in use. Change LISTEN_PORT or close the app using it.")
    }

    private suspend fun ensureGoCoreStopped() {
        if (!mobile.Mobile.isRunning()) return
        VpnManager.appendLog("Go core is still running, stopping it first...")
        runCatching { mobile.Mobile.stopClient() }
        
        repeat(20) {
            delay(200L)
            if (!mobile.Mobile.isRunning()) {
                VpnManager.appendLog("Go core stopped successfully")
                return
            }
        }
        VpnManager.appendLog("Warning: Go core may still be running")
    }

    private fun isLocalPortInUse(port: Int): Boolean {
        return runCatching {
            ServerSocket().use { server ->
                server.reuseAddress = true
                server.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), port))
            }
            false
        }.getOrElse { true }
    }

    private suspend fun tailLogFile(logFile: File) {
        // Continuously mirrors Go log file into Compose logs so Android UI shows real progress.
        RandomAccessFile(logFile, "r").use { raf ->
            var pointer = 0L
            while (coroutineContext.isActive) {
                val length = raf.length()
                if (length < pointer) {
                    pointer = 0L
                }

                if (length > pointer) {
                    raf.seek(pointer)
                    while (true) {
                        val line = raf.readLine() ?: break
                        if (line.isNotBlank()) {
                            VpnManager.appendCoreLog(line)
                            maybeReportSocksAuthIssue(line)
                            maybeReportSessionBusyIssue(line)
                        }
                    }
                    pointer = raf.filePointer
                }

                delay(250L)
            }
        }
    }

    private fun maybeReportSocksAuthIssue(line: String) {
        if (socksAuthWarningShown) return
        val normalized = line.uppercase()
        val authRelatedFailure = normalized.contains("SOCKS5_AUTH_FAILED") ||
            (normalized.contains("SOCKS5") &&
                normalized.contains("AUTH") &&
                normalized.contains("FAIL"))
        if (!authRelatedFailure) return

        socksAuthWarningShown = true
        val message = "SOCKS5 authentication failed. Check SOCKS5_AUTH, SOCKS5_USER, and SOCKS5_PASS in profile settings."
        VpnManager.appendLog(message)
        VpnManager.setError(message)
    }

    private fun maybeReportSessionBusyIssue(line: String) {
        if (sessionBusyWarningShown) return
        val normalized = line.uppercase()
        val isSessionBusy = normalized.contains("SESSION RESTART REQUESTED: SESSION BUSY RECEIVED")
        if (!isSessionBusy) return

        sessionBusyWarningShown = true
        val message = "Server is busy and cannot accept new sessions at the moment."
        VpnManager.appendLog(message)
        VpnManager.setError(message)
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$TAG:runtime").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWakeLock() {
        val lock = wakeLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wakeLock = null
    }

    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        val wm = applicationContext.getSystemService(WIFI_SERVICE) as? WifiManager ?: return
        wifiLock = wm.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$TAG:wifi").apply {
            setReferenceCounted(false)
            acquire()
        }
    }

    private fun releaseWifiLock() {
        val lock = wifiLock ?: return
        if (lock.isHeld) {
            runCatching { lock.release() }
        }
        wifiLock = null
    }

    private fun startKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = serviceScope.launch {
            while (isActive) {
                delay(15_000L)
                VpnManager.appendCoreLog("keepalive: service alive")
            }
        }
    }

    private suspend fun startInternetSharing(socksPort: Int, httpPort: Int, username: String, password: String) {
        // Ensure ports are available before starting
        if (isLocalPortInUse(socksPort) || isLocalPortInUse(httpPort)) {
            VpnManager.appendLog("Sharing ports in use, attempting to free...")
            if (mobile.Mobile.isRunning()) {
                runCatching { mobile.Mobile.stopClient() }
            }
            delay(500L)
        }

        sharingSocksJob?.cancel()
        sharingSocksServer?.close()
        sharingSocksServer = null

        sharingSocksJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(socksPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                sharingSocksServer = server
                VpnManager.appendLog("Sharing SOCKS5 proxy ready on 0.0.0.0:$socksPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleSharingSocksClient(client)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Sharing SOCKS5 proxy error", e)
                VpnManager.appendLog("Sharing SOCKS5 proxy error: ${e.message}")
            }
        }

        httpProxyJob?.cancel()
        sharingHttpServer?.close()
        sharingHttpServer = null

        httpProxyJob = serviceScope.launch {
            try {
                val server = java.net.ServerSocket(httpPort, 50, InetAddress.getByName("0.0.0.0"))
                server.reuseAddress = true
                sharingHttpServer = server
                VpnManager.appendLog("HTTP proxy ready on 0.0.0.0:$httpPort")
                while (isActive) {
                    val client = server.accept() ?: continue
                    launch(Dispatchers.IO) {
                        handleHttpProxyClient(client, socksPort, username, password)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "HTTP proxy error", e)
                VpnManager.appendLog("HTTP proxy error: ${e.message}")
            }
        }
    }

    private suspend fun handleSharingSocksClient(client: java.net.Socket) {
        var upstream: java.net.Socket? = null
        try {
            upstream = java.net.Socket("127.0.0.1", activeLocalSocksPort)
            upstream.soTimeout = 30000
            bridgeBidirectional(client, upstream)
        } catch (_: Exception) {
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private fun readLineUnbuffered(input: java.io.InputStream): String? {
        val bytes = ArrayList<Byte>()
        while (true) {
            val next = input.read()
            if (next < 0) {
                if (bytes.isEmpty()) return null
                break
            }
            if (next == '\n'.code) break
            if (next != '\r'.code) {
                bytes.add(next.toByte())
            }
        }
        return String(bytes.toByteArray(), Charsets.ISO_8859_1)
    }

    private suspend fun handleHttpProxyClient(client: java.net.Socket, upstreamSocksPort: Int, username: String, password: String) {
        try {
            val input = client.getInputStream()
            val output = client.getOutputStream().bufferedWriter()

            val requestLine = readLineUnbuffered(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }

            val method = parts[0]
            val url = parts[1]

            var authHeader: String? = null
            while (true) {
                val line = readLineUnbuffered(input) ?: break
                if (line.isBlank()) break
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                    authHeader = value
                }
            }

            val requiresAuth = username.isNotBlank() || password.isNotBlank()
            if (requiresAuth && !isValidBasicProxyAuth(authHeader, username, password)) {
                output.write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"GooseRelayVPN\"\r\n" +
                        "Connection: close\r\n\r\n"
                )
                output.flush()
                return
            }

            if (method == "CONNECT") {
                val hostPort = url.split(":")
                val host = hostPort[0]
                val port = hostPort.getOrElse(1) { "80" }.toIntOrNull() ?: 80

                output.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                output.flush()

                val upstream = createSocks5Tunnel(upstreamSocksPort, host, port)
                upstream.soTimeout = 30000

                bridgeBidirectional(client, upstream)
            } else {
                output.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n")
                output.flush()
            }
} catch (_: Exception) {}
        runCatching { client.close() }
    }

    private suspend fun bridgeBidirectional(client: java.net.Socket, upstream: java.net.Socket) = coroutineScope {
        val upToClient = launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                val input = upstream.getInputStream()
                val output = client.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { client.shutdownOutput() }
            }
        }

        val clientToUp = launch(Dispatchers.IO) {
            val buffer = ByteArray(8192)
            try {
                val input = client.getInputStream()
                val output = upstream.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                    output.flush()
                }
            } catch (_: Exception) {
            } finally {
                runCatching { upstream.shutdownOutput() }
            }
        }

        joinAll(upToClient, clientToUp)
        runCatching { upstream.close() }
        runCatching { client.close() }
    }

    private fun createSocks5Tunnel(socksPort: Int, targetHost: String, targetPort: Int): java.net.Socket {
        val socket = java.net.Socket("127.0.0.1", socksPort)
        socket.soTimeout = 15000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        output.write(byteArrayOf(0x05, 0x01, 0x00))
        output.flush()
        val greeting = ByteArray(2)
        readFully(input, greeting, 0, greeting.size)
        if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 upstream greeting failed")
        }

        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) {
            throw IllegalArgumentException("Target host is too long")
        }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val header = ByteArray(4)
        readFully(input, header, 0, header.size)
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 connect failed with code ${header[1].toInt() and 0xFF}")
        }

        val addrLen = when (header[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> {
                val size = input.read()
                if (size < 0) throw IllegalStateException("SOCKS5 malformed bind address length")
                size
            }
            0x04 -> 16
            else -> throw IllegalStateException("SOCKS5 unsupported bind address type")
        }
        val skip = ByteArray(addrLen + 2)
        readFully(input, skip, 0, skip.size)
        return socket
    }

    private fun isValidBasicProxyAuth(header: String?, username: String, password: String): Boolean {
        if (username.isBlank() && password.isBlank()) return true
        val value = header?.trim().orEmpty()
        if (!value.startsWith("Basic ", ignoreCase = true)) return false
        val encoded = value.substringAfter(" ", "").trim()
        if (encoded.isBlank()) return false
        val decoded = runCatching {
            val bytes = android.util.Base64.decode(encoded, android.util.Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        }.getOrNull() ?: return false
        return decoded == "$username:$password"
    }

    private fun readFully(input: java.io.InputStream, buffer: ByteArray, offset: Int, length: Int) {
        var total = 0
        while (total < length) {
            val read = input.read(buffer, offset + total, length - total)
            if (read < 0) throw IllegalStateException("Unexpected EOF while reading SOCKS5 response")
            total += read
        }
    }
}

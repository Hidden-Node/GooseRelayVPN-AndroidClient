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
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import com.gooserelay.gooserelayvpn.util.GlobalSettingsStore
import com.gooserelay.gooserelayvpn.util.VpnManager
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.coroutines.coroutineContext
import javax.inject.Inject

@AndroidEntryPoint
class GooseRelayVpnService : VpnService() {

    @Inject lateinit var profileRepository: ProfileRepository

    companion object {
        const val ACTION_CONNECT = "com.gooserelay.gooserelayvpn.CONNECT"
        const val ACTION_DISCONNECT = "com.gooserelay.gooserelayvpn.DISCONNECT"
        const val EXTRA_PROFILE_ID = "profile_id"
        private const val TAG = "GooseRelayVPN"
        private const val NOTIFICATION_ID = 1
        private const val DEFAULT_SOCKS_PORT = 1080
        private const val MAX_SHARING_CONNECTIONS = 64
        private const val SHARING_REJECT_LOG_INTERVAL_MS = 10_000L
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
    private var sharingHttpJob: Job? = null
    private var sharingSocksJob: Job? = null
    private var sharingSocksServer: java.net.ServerSocket? = null
    private var sharingHttpServer: java.net.ServerSocket? = null
    private val sharingConnections = java.util.Collections.synchronizedSet(mutableSetOf<java.net.Socket>())
    // Throttle bookkeeping for the reject log line below. Guarded by
    // synchronizing on sharingConnections (same monitor the set uses).
    private var lastSharingRejectLogMs = 0L
    private var suppressedSharingRejects = 0
    private val sharingStartStopMutex = kotlinx.coroutines.sync.Mutex()
    private val sharingGeneration = java.util.concurrent.atomic.AtomicInteger(0)
    private var logTailJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var keepaliveJob: Job? = null
    private var isStopping = false
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
        if (intent == null) {
            // Sticky restart after a system kill: no action to take (no
            // auto-reconnect by design). The service is now "started", so it
            // must enter the foreground once to avoid
            // ForegroundServiceDidNotStartInTimeException on Android 12+,
            // then stop cleanly.
            runCatching {
                startForeground(NOTIFICATION_ID, buildNotification(getString(R.string.notification_disconnected)))
            }.onFailure { Log.w(TAG, "startForeground on restart failed", it) }
            VpnManager.updateState(VpnManager.VpnState.DISCONNECTED)
            // startId-qualified: stop only if no newer start (e.g. a real
            // ACTION_CONNECT) arrived after this zombie restart.
            stopSelf(startId)
            return START_NOT_STICKY
        }
        when (intent.action) {
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
        // A new session is starting on this instance: re-enable the network
        // callback (stopVpn() left isStopping=true on purpose so onDestroy
        // skips double-cleanup; a same-instance reconnect must clear it).
        isStopping = false
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
                val profile = profileRepository.getProfileById(profileId)
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
                    val sharingSocksPort = globalSettings.internetSharingSocksPort
                    val sharingHttpPort = globalSettings.internetSharingHttpPort
                    val user = globalSettings.internetSharingUser
                    val pass = globalSettings.internetSharingPass
                    startInternetSharing(sharingSocksPort, sharingHttpPort, activeLocalSocksPort, user, pass)
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
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                    builder.setUnderlyingNetworks(null) // API 22+: no-op fix for Android 5.0 crash
                }
                
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
                        
                        // Start TUN bridge: fd, mtu, socksAddr, socksUser, socksPass.
                        // The Go core's SOCKS5 server enforces user/pass auth when
                        // both fields are non-empty; we forward them so the FakeDNS
                        // proxy can negotiate through it.
                        mobile.Mobile.startTunBridge(
                            vpnInterface!!.fd.toLong(),
                            1500L,
                            "127.0.0.1:$socksPort",
                            profile.socksUser,
                            profile.socksPass,
                        )
                        
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
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
                            setUnderlyingNetworks(arrayOf(network))
                        }
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
                sharingSocksJob?.cancel()
                sharingHttpJob?.cancel()
                keepaliveJob?.cancel()
                logTailJob?.cancel()

                networkCallback?.let {
                    runCatching {
                        val cm = getSystemService(android.content.Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
                        cm.unregisterNetworkCallback(it)
                    }
                    networkCallback = null
                }

                // Close sharing servers (also closes tracked client sockets)
                stopSharingServers()

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

    // Rejects can arrive in bursts (hostile or buggy LAN client); log at
    // most one line per interval so the reject line can't evict useful
    // entries from the 2000-line log ring. Rejection itself stays
    // per-connection and unconditional — only the log is throttled.
    private fun logSharingRejectThrottled() {
        synchronized(sharingConnections) {
            val now = System.currentTimeMillis()
            if (now - lastSharingRejectLogMs < SHARING_REJECT_LOG_INTERVAL_MS) {
                suppressedSharingRejects++
                return
            }
            lastSharingRejectLogMs = now
            val suppressed = suppressedSharingRejects
            suppressedSharingRejects = 0
            VpnManager.appendLog(
                "Sharing connection limit reached; rejecting client" +
                    if (suppressed > 0) " ($suppressed similar rejects suppressed)" else ""
            )
        }
    }

    private suspend fun startInternetSharing(
        socksPort: Int,
        httpPort: Int,
        coreSocksPort: Int,
        username: String,
        password: String
    ) {
        sharingStartStopMutex.withLock {
            stopSharingServers()

            // ponytail: both-or-neither — half-blank creds are rejected instead of falling back to
            // an open or locked proxy. UI flags this too; this is the trust-boundary enforcement.
            val userBlank = username.isBlank()
            val passBlank = password.isBlank()
            if (userBlank != passBlank) {
                throw IllegalStateException(
                    "Internet Sharing requires both username and password, or neither. Set both in Settings."
                )
            }
            val authEnabled = !userBlank && !passBlank
            ensureSharingPortFree(socksPort, coreSocksPort)
            ensureSharingPortFree(httpPort, coreSocksPort)

            val myGeneration = sharingGeneration.get()
            sharingSocksJob = serviceScope.launch {
                try {
                    val server = java.net.ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), socksPort), 50)
                    }
                    if (sharingGeneration.get() != myGeneration) { runCatching { server.close() }; return@launch }
                    sharingSocksServer = server
                    VpnManager.appendLog(
                        "Sharing SOCKS5 proxy ready on 0.0.0.0:$socksPort" +
                            if (authEnabled) " (auth enabled)" else " (open, no auth)"
                    )
                    while (isActive) {
                        val client = server.accept()
                        if (!isActive) { runCatching { client.close() }; break }
                        if (synchronized(sharingConnections) { sharingConnections.size >= MAX_SHARING_CONNECTIONS }) {
                            logSharingRejectThrottled()
                            runCatching { client.close() }
                            continue
                        }
                        launch(Dispatchers.IO) {
                            sharingConnections.add(client)
                            try {
                                handleSharingSocksClient(client, coreSocksPort, username, password)
                            } finally {
                                sharingConnections.remove(client)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "Sharing SOCKS5 proxy error", e)
                    VpnManager.appendLog("Sharing SOCKS5 proxy error: ${e.message}")
                }
            }

            sharingHttpJob = serviceScope.launch {
                try {
                    val server = java.net.ServerSocket().apply {
                        reuseAddress = true
                        bind(InetSocketAddress(InetAddress.getByName("0.0.0.0"), httpPort), 50)
                    }
                    if (sharingGeneration.get() != myGeneration) { runCatching { server.close() }; return@launch }
                    sharingHttpServer = server
                    VpnManager.appendLog(
                        "HTTP proxy ready on 0.0.0.0:$httpPort" +
                            if (authEnabled) " (auth enabled)" else " (open, no auth)"
                    )
                    while (isActive) {
                        val client = server.accept()
                        if (!isActive) { runCatching { client.close() }; break }
                        if (synchronized(sharingConnections) { sharingConnections.size >= MAX_SHARING_CONNECTIONS }) {
                            logSharingRejectThrottled()
                            runCatching { client.close() }
                            continue
                        }
                        launch(Dispatchers.IO) {
                            sharingConnections.add(client)
                            try {
                                handleHttpProxyClient(client, coreSocksPort, username, password)
                            } finally {
                                sharingConnections.remove(client)
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    Log.e(TAG, "HTTP proxy error", e)
                    VpnManager.appendLog("HTTP proxy error: ${e.message}")
                }
            }
        }
    }

    private fun stopSharingServers() {
        sharingGeneration.incrementAndGet()
        sharingSocksJob?.cancel()
        sharingHttpJob?.cancel()
        runCatching { sharingSocksServer?.close() }
        runCatching { sharingHttpServer?.close() }
        sharingSocksServer = null
        sharingHttpServer = null
        val open = synchronized(sharingConnections) { sharingConnections.toList().also { sharingConnections.clear() } }
        open.forEach { c -> runCatching { c.close() } }
    }

    private suspend fun ensureSharingPortFree(port: Int, coreSocksPort: Int) {
        if (!isLocalPortInUse(port)) return
        VpnManager.appendLog("Sharing port $port is busy; freeing our own resources...")
        // Cancel our sharing jobs and close our servers; never touch the Go core.
        stopSharingServers()
        repeat(15) {
            delay(200L)
            if (!isLocalPortInUse(port)) {
                VpnManager.appendLog("Sharing port $port is now free")
                return
            }
        }
        if (port == coreSocksPort) {
            throw IllegalStateException(
                "Sharing port $port is the VPN's internal SOCKS5 port. Pick a different sharing port in Settings."
            )
        }
        throw IllegalStateException(
            "Sharing port $port is in use by another app. Change it in Settings."
        )
    }

    private suspend fun handleSharingSocksClient(client: java.net.Socket, coreSocksPort: Int, username: String, password: String) {
        var upstream: java.net.Socket? = null
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val authRequired = username.isNotBlank() && password.isNotBlank()

            // --- SOCKS5 greeting (RFC 1928) ---
            val header = ByteArray(2)
            readFully(input, header, 0, 2)
            if (header[0] != 0x05.toByte()) return
            val nMethods = header[1].toInt() and 0xFF
            if (nMethods == 0) return
            val methods = ByteArray(nMethods)
            readFully(input, methods, 0, nMethods)

            if (authRequired) {
                if (!methods.any { it == 0x02.toByte() }) {
                    output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush(); return
                }
                output.write(byteArrayOf(0x05, 0x02)); output.flush()
                // --- RFC 1929 user/pass sub-negotiation ---
                val subVersion = input.read()
                if (subVersion != 0x01) { output.write(byteArrayOf(0x01, 0x01)); output.flush(); return }
                val ulen = input.read()
                if (ulen < 0) return
                val ub = ByteArray(ulen)
                readFully(input, ub, 0, ulen)
                val plen = input.read()
                if (plen < 0) return
                val pb = ByteArray(plen)
                readFully(input, pb, 0, plen)
                val ok = constantTimeEquals(ub, username.toByteArray(Charsets.UTF_8)) &&
                    constantTimeEquals(pb, password.toByteArray(Charsets.UTF_8))
                output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                output.flush()
                if (!ok) return
            } else {
                output.write(byteArrayOf(0x05, 0x00)); output.flush()
            }

            // --- SOCKS5 request ---
            val req = ByteArray(4)
            readFully(input, req, 0, 4)
            if (req[0] != 0x05.toByte()) return
            if (req[1] != 0x01.toByte()) {
                // 0x07 = command not supported
                output.write(byteArrayOf(0x05, 0x07, 0x00)); output.flush(); return
            }
            val host = when (req[3].toInt() and 0xFF) {
                0x01 -> { val b = ByteArray(4); readFully(input, b, 0, 4); b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
                0x03 -> { val l = input.read(); if (l < 0) return; val b = ByteArray(l); readFully(input, b, 0, l); String(b, Charsets.UTF_8) }
                0x04 -> { val b = ByteArray(16); readFully(input, b, 0, 16); java.net.InetAddress.getByAddress(b).hostAddress ?: return }
                else -> { output.write(byteArrayOf(0x05, 0x08, 0x00)); output.flush(); return }
            }
            val portBytes = ByteArray(2); readFully(input, portBytes, 0, 2)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            upstream = try { createSocks5Tunnel(coreSocksPort, host, port) } catch (e: Exception) {
                VpnManager.appendLog("Sharing SOCKS5 upstream to $host:$port failed: ${e.message}")
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
                return
            }
            // Handshake is done: idle timeouts off. A quiet tunnel (SSH,
            // WebSocket) must not be killed by a read timeout.
            upstream.soTimeout = 0
            client.soTimeout = 0
            // 0x05 0x00 0x00 0x01 + 4-byte bind addr + 2-byte bind port
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()

            bridgeBidirectional(client, upstream)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            VpnManager.appendLog("Sharing SOCKS5 client error: ${e.message}")
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    private suspend fun handleHttpProxyClient(client: java.net.Socket, upstreamSocksPort: Int, username: String, password: String) {
        try {
            client.soTimeout = 15000
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
            val headerLines = ArrayList<String>()
            while (true) {
                val line = readLineUnbuffered(input) ?: break
                if (line.isBlank()) break
                headerLines.add(line)
                if (headerLines.size > 100) {
                    output.write("HTTP/1.1 431 Request Header Fields Too Large\r\nConnection: close\r\n\r\n"); output.flush()
                    return
                }
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                    authHeader = value
                }
            }

            val requiresAuth = username.isNotBlank() && password.isNotBlank()
            if (requiresAuth && !isValidBasicProxyAuth(authHeader, username, password)) {
                output.write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"GooseRelayVPN\"\r\n" +
                        "Connection: close\r\n\r\n"
                )
                output.flush()
                return
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                val target = parseProxyTarget("CONNECT", url)
                if (target == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\n\r\n"); output.flush()
                    return
                }
                val upstream = try {
                    createSocks5Tunnel(upstreamSocksPort, target.host, target.port)
                } catch (e: Exception) {
                    VpnManager.appendLog("Sharing HTTP CONNECT to ${target.host}:${target.port} failed: ${e.message}")
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n"); output.flush()
                    return
                }
                upstream.soTimeout = 0
                client.soTimeout = 0
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                output.flush()
                bridgeBidirectional(client, upstream)
            } else {
                val target = parseProxyTarget(method, url)
                if (target == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"); output.flush()
                    return
                }
                val upstream = try {
                    createSocks5Tunnel(upstreamSocksPort, target.host, target.port)
                } catch (e: Exception) {
                    VpnManager.appendLog("Sharing HTTP $method to ${target.host}:${target.port} failed: ${e.message}")
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"); output.flush()
                    return
                }
                upstream.soTimeout = 0
                client.soTimeout = 0
                // Re-emit the request with a relative path (origin-form) to
                // the tunnel, then bridge; the tunnel's SOCKS5 target is
                // already resolved by createSocks5Tunnel.
                val forwardedHeaders = headerLines
                    .filter { line ->
                        val idx = line.indexOf(':')
                        if (idx <= 0) return@filter true
                        val name = line.substring(0, idx).trim()
                        !name.equals("Host", ignoreCase = true) &&
                            !name.equals("Proxy-Authorization", ignoreCase = true)
                    }
                    .joinToString("") { "$it\r\n" }
                val rewritten = buildString {
                    append(method).append(' ').append(target.path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(target.host)
                    if (target.port != 80) append(':').append(target.port)
                    append("\r\n")
                    append(forwardedHeaders)
                    append("\r\n")
                }
                val upstreamOut = upstream.getOutputStream()
                upstreamOut.write(rewritten.toByteArray(Charsets.ISO_8859_1))
                upstreamOut.flush()
                bridgeBidirectional(client, upstream)
            }
} catch (_: Exception) {} finally {
        runCatching { client.close() }
    }
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
}

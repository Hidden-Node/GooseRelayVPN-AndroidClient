package com.gooserelay.gooserelayvpn.util

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.service.GooseRelayVpnService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Singleton bridge between Kotlin UI and Go core.
 * Manages VPN lifecycle and connection state.
 */
object VpnManager {

    enum class VpnState {
        DISCONNECTED, CONNECTING, CONNECTED, DISCONNECTING, ERROR
    }
    enum class LogSource {
        CORE, ANDROID
    }
    data class LogEntry(
        val line: String,
        val source: LogSource
    )

    private val _state = MutableStateFlow(VpnState.DISCONNECTED)
    val state: StateFlow<VpnState> = _state.asStateFlow()

    private val logBuffer = ArrayDeque<LogEntry>(MAX_LOG_LINES)
    private val _logEntries = MutableStateFlow<List<LogEntry>>(emptyList())
    val logEntries: StateFlow<List<LogEntry>> = _logEntries.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _uploadSpeedBps = MutableStateFlow(0L)
    val uploadSpeedBps: StateFlow<Long> = _uploadSpeedBps.asStateFlow()
    private val _downloadSpeedBps = MutableStateFlow(0L)
    val downloadSpeedBps: StateFlow<Long> = _downloadSpeedBps.asStateFlow()

    private val _uploadTotalBytes = MutableStateFlow(0L)
    val uploadTotalBytes: StateFlow<Long> = _uploadTotalBytes.asStateFlow()
    private val _downloadTotalBytes = MutableStateFlow(0L)
    val downloadTotalBytes: StateFlow<Long> = _downloadTotalBytes.asStateFlow()
    private val _connectedDurationSeconds = MutableStateFlow(0L)
    val connectedDurationSeconds: StateFlow<Long> = _connectedDurationSeconds.asStateFlow()

    data class ScanStatus(
        val statsActive: Int = 0,
        val statsSessionsOpen: Int = 0,
        val statsSessionsClose: Int = 0,
        val statsBytesOut: String = "",
        val statsBytesIn: String = "",
        val statsPollsOk: Int = 0,
        val statsPollsFail: Int = 0,
        val accountStats: String = "",
        val hasStats: Boolean = false
    )

    private val _scanStatus = MutableStateFlow(ScanStatus())
    val scanStatus: StateFlow<ScanStatus> = _scanStatus.asStateFlow()

    private val monitorScope = CoroutineScope(Dispatchers.Default)
    private var trafficMonitorJob: Job? = null

    private const val MAX_LOG_LINES = 2000
    private val stampRegex = Regex("^\\d{4}[-/]\\d{2}[-/]\\d{2}\\s+\\d{2}:\\d{2}:\\d{2}")

    private val STATS_ACTIVE_REGEX = Regex("active=(\\d+)", RegexOption.IGNORE_CASE)
    private val STATS_SESSIONS_REGEX = Regex("sessions=(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    private val STATS_BYTES_REGEX = Regex("bytes=([^/\\s]+)/([^\\s]+)", RegexOption.IGNORE_CASE)
    private val STATS_POLLS_REGEX = Regex("polls=(\\d+)/(\\d+)", RegexOption.IGNORE_CASE)
    private val STATS_ACCOUNTS_REGEX = Regex("accounts=\\[(.+)\\]", RegexOption.IGNORE_CASE)

    private data class TimestampCandidate(
        val regex: Regex,
        val inputPattern: String,
        val outputPattern: String
    )

    private val TIMESTAMP_CANDIDATES = listOf(
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}\\.\\d{3}Z)(.*)$"),
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd HH:mm:ss.SSS"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)(.*)$"),
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd HH:mm:ss"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s+UTC(.*)$"),
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\s(.*)$"),
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss"
        ),
        TimestampCandidate(
            Regex("^(\\d{4}/\\d{2}/\\d{2} \\d{2}:\\d{2}:\\d{2})(.*)$"),
            "yyyy/MM/dd HH:mm:ss",
            "yyyy/MM/dd HH:mm:ss"
        )
    )

    private val candidateFormats: Map<TimestampCandidate, Pair<SimpleDateFormat, SimpleDateFormat>> =
        TIMESTAMP_CANDIDATES.associateWith { c ->
            val input = SimpleDateFormat(c.inputPattern, Locale.US).apply {
                timeZone = TimeZone.getTimeZone("UTC")
                isLenient = false
            }
            val output = SimpleDateFormat(c.outputPattern, Locale.US).apply {
                timeZone = TimeZone.getDefault()
            }
            Pair(input, output)
        }

    fun updateState(newState: VpnState) {
        _state.value = newState
        if (newState == VpnState.CONNECTING) {
            _connectedDurationSeconds.value = 0L
            _uploadTotalBytes.value = 0L
            _downloadTotalBytes.value = 0L
        }
    }

    fun setError(message: String) {
        _errorMessage.value = message
        _state.value = VpnState.ERROR
    }

    fun clearError() {
        _errorMessage.value = null
    }

    fun appendLog(line: String) {
        appendLogInternal(line, LogSource.ANDROID)
    }

    fun appendCoreLog(line: String) {
        appendLogInternal(line, LogSource.CORE)
    }

    private fun appendLogInternal(line: String, source: LogSource) {
        var normalizedLine = normalizeLogTimestampToLocal(line)
        if (!stampRegex.containsMatchIn(normalizedLine)) {
            normalizedLine = "${nowStamp()} $normalizedLine"
        }
        val entry = LogEntry(normalizedLine, source)
        val snapshot: List<LogEntry>
        synchronized(logBuffer) {
            logBuffer.addLast(entry)
            if (logBuffer.size > MAX_LOG_LINES) logBuffer.removeFirst()
            snapshot = logBuffer.toList()
        }
        _logEntries.value = snapshot
        parseScanLine(normalizedLine)
    }

    private fun nowStamp(): String =
        synchronized(stampFormat) { stampFormat.format(Date()) }

    private val stampFormat = SimpleDateFormat("yyyy/MM/dd HH:mm:ss", Locale.US)

    fun clearLogs() {
        synchronized(logBuffer) {
            logBuffer.clear()
        }
        _logEntries.value = emptyList()
        _scanStatus.value = ScanStatus()
    }

    fun startTrafficMonitor(context: Context) {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = monitorScope.launch {
            var prevTx = 0L
            var prevRx = 0L
            var prevTime = System.currentTimeMillis()
            val startedAt = prevTime
            _uploadTotalBytes.value = 0L
            _downloadTotalBytes.value = 0L
            _connectedDurationSeconds.value = 0L
            while (isActive) {
                delay(1000L)
                val now = System.currentTimeMillis()
                val bandwidth = mobile.Mobile.getTunBandwidth()
                val tx = bandwidth?.up ?: 0L
                val rx = bandwidth?.down ?: 0L
                val dt = (now - prevTime).coerceAtLeast(1L)
                val uploadDelta = (tx - prevTx).coerceAtLeast(0L)
                val downloadDelta = (rx - prevRx).coerceAtLeast(0L)
                _uploadSpeedBps.value = (uploadDelta * 1000L) / dt
                _downloadSpeedBps.value = (downloadDelta * 1000L) / dt
                _uploadTotalBytes.value = tx
                _downloadTotalBytes.value = rx
                _connectedDurationSeconds.value = ((now - startedAt) / 1000L).coerceAtLeast(0L)
                prevTx = tx
                prevRx = rx
                prevTime = now
            }
        }
    }

    fun stopTrafficMonitor() {
        trafficMonitorJob?.cancel()
        trafficMonitorJob = null
        _uploadSpeedBps.value = 0L
        _downloadSpeedBps.value = 0L
    }

    /**
     * Start the VPN service.
     */
    fun connect(context: Context, profile: ProfileEntity) {
        if (_state.value == VpnState.CONNECTED || _state.value == VpnState.CONNECTING) return

        updateState(VpnState.CONNECTING)
        clearError()
        _scanStatus.value = ScanStatus()

        val intent = Intent(context, GooseRelayVpnService::class.java).apply {
            action = GooseRelayVpnService.ACTION_CONNECT
            putExtra(GooseRelayVpnService.EXTRA_PROFILE_ID, profile.id)
        }

        runCatching { context.startService(intent) }
            .onFailure {
                setError("Failed to start VPN service: ${it.message}")
                appendLog("Failed to start VPN service: ${it.message}")
                updateState(VpnState.ERROR)
            }
    }

    /**
     * Stop the VPN service.
     */
    fun disconnect(context: Context) {
        if (_state.value == VpnState.DISCONNECTED || _state.value == VpnState.DISCONNECTING) return

        updateState(VpnState.DISCONNECTING)
        stopTrafficMonitor()

        val intent = Intent(context, GooseRelayVpnService::class.java).apply {
            action = GooseRelayVpnService.ACTION_DISCONNECT
        }
        runCatching { context.startService(intent) }
            .onFailure {
                setError("Failed to stop VPN service: ${it.message}")
                appendLog("Failed to stop VPN service: ${it.message}")
                updateState(VpnState.ERROR)
            }
    }

    private fun parseScanLine(line: String) {
        // New format: [stats] active=8 sessions=70/62 frames=259/365 bytes=13.8MB/883.5KB polls=404/0 rst=1 endpoints=3/3
        val active = STATS_ACTIVE_REGEX.find(line)
            ?.groupValues?.getOrNull(1)?.toIntOrNull()

        val sessions = STATS_SESSIONS_REGEX.find(line)
        val sessionsOpen = sessions?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val sessionsClose = sessions?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        val bytes = STATS_BYTES_REGEX.find(line)
        val bytesOut = bytes?.groupValues?.getOrNull(1) ?: ""
        val bytesIn = bytes?.groupValues?.getOrNull(2) ?: ""

        val polls = STATS_POLLS_REGEX.find(line)
        val pollsOk = polls?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 0
        val pollsFail = polls?.groupValues?.getOrNull(2)?.toIntOrNull() ?: 0

        // Accounts format: accounts=[air today=259 script=514 | mr4 today=258 script=506 | mr6 today=258 script=515]
        val accounts = STATS_ACCOUNTS_REGEX.find(line)
        val accountStats = accounts?.groupValues?.getOrNull(1)?.trim() ?: ""

        if ((active != null && (bytesOut.isNotBlank() || sessionsOpen > 0)) || (accounts != null && accountStats.isNotBlank())) {
            _scanStatus.value = _scanStatus.value.copy(
                statsActive = active ?: _scanStatus.value.statsActive,
                statsSessionsOpen = if (sessions != null) sessionsOpen else _scanStatus.value.statsSessionsOpen,
                statsSessionsClose = if (sessions != null) sessionsClose else _scanStatus.value.statsSessionsClose,
                statsBytesOut = if (bytes != null) bytesOut else _scanStatus.value.statsBytesOut,
                statsBytesIn = if (bytes != null) bytesIn else _scanStatus.value.statsBytesIn,
                statsPollsOk = if (polls != null) pollsOk else _scanStatus.value.statsPollsOk,
                statsPollsFail = if (polls != null) pollsFail else _scanStatus.value.statsPollsFail,
                accountStats = if (accounts != null) accountStats else _scanStatus.value.accountStats,
                hasStats = true
            )
        }
    }

    private fun normalizeLogTimestampToLocal(line: String): String {
        for (candidate in TIMESTAMP_CANDIDATES) {
            val match = candidate.regex.find(line) ?: continue
            val utcStamp = match.groupValues[1]
            val suffix = match.groupValues[2]
            val localStamp = convertUtcToLocal(utcStamp, candidate) ?: continue
            return "$localStamp$suffix"
        }
        return line
    }

    private fun convertUtcToLocal(
        utcValue: String,
        candidate: TimestampCandidate
    ): String? {
        val formats = candidateFormats[candidate] ?: return null
        val (input, output) = formats
        return try {
            val parsed: Date = synchronized(input) { input.parse(utcValue) } ?: return null
            synchronized(output) {
                output.timeZone = TimeZone.getDefault()
                output.format(parsed)
            }
        } catch (_: Exception) {
            null
        }
    }
}

package com.gooserelay.gooserelayvpn.util

import java.util.Locale

object TrafficFormatters {
    fun formatSpeed(bps: Long): String {
        val safeBps = bps.coerceAtLeast(0L)
        val kbps = safeBps / 1024.0
        val mbps = kbps / 1024.0
        val gbps = mbps / 1024.0
        return when {
            gbps >= 1.0 -> String.format(Locale.US, "%.2f GB/s", gbps)
            mbps >= 1.0 -> String.format(Locale.US, "%.2f MB/s", mbps)
            kbps >= 1.0 -> String.format(Locale.US, "%.1f KB/s", kbps)
            else -> String.format(Locale.US, "%d B/s", safeBps)
        }
    }

    fun formatBytes(bytes: Long): String {
        val safeBytes = bytes.coerceAtLeast(0L)
        val kb = safeBytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0
        return when {
            gb >= 1.0 -> String.format(Locale.US, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.US, "%.2f MB", mb)
            kb >= 1.0 -> String.format(Locale.US, "%.1f KB", kb)
            else -> String.format(Locale.US, "%d B", safeBytes)
        }
    }

    fun formatDuration(seconds: Long): String {
        val s = seconds.coerceAtLeast(0L)
        val hrs = s / 3600
        val mins = (s % 3600) / 60
        val secs = s % 60
        return if (hrs > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", hrs, mins, secs)
        } else {
            String.format(Locale.US, "%02d:%02d", mins, secs)
        }
    }
}

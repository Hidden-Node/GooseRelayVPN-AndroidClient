package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.util.TrafficFormatters
import org.junit.Test

class TrafficFormattersTest {

    @Test
    fun formatSpeed_formatsExactUnits() {
        assertThat(TrafficFormatters.formatSpeed(0L)).isEqualTo("0 B/s")
        assertThat(TrafficFormatters.formatSpeed(-50L)).isEqualTo("0 B/s")
        assertThat(TrafficFormatters.formatSpeed(512L)).isEqualTo("512 B/s")
        assertThat(TrafficFormatters.formatSpeed(1536L)).isEqualTo("1.5 KB/s")
        assertThat(TrafficFormatters.formatSpeed(2 * 1024 * 1024L)).isEqualTo("2.00 MB/s")
        assertThat(TrafficFormatters.formatSpeed((1.5 * 1024 * 1024 * 1024).toLong())).isEqualTo("1.50 GB/s")
    }

    @Test
    fun formatBytes_formatsExactUnits() {
        assertThat(TrafficFormatters.formatBytes(0L)).isEqualTo("0 B")
        assertThat(TrafficFormatters.formatBytes(-100L)).isEqualTo("0 B")
        assertThat(TrafficFormatters.formatBytes(500L)).isEqualTo("500 B")
        assertThat(TrafficFormatters.formatBytes(1500L)).isEqualTo("1.5 KB")
        assertThat(TrafficFormatters.formatBytes(10 * 1024 * 1024L)).isEqualTo("10.00 MB")
        assertThat(TrafficFormatters.formatBytes((2.25 * 1024 * 1024 * 1024).toLong())).isEqualTo("2.25 GB")
    }

    @Test
    fun formatDuration_formatsMinutesAndHours() {
        assertThat(TrafficFormatters.formatDuration(0L)).isEqualTo("00:00")
        assertThat(TrafficFormatters.formatDuration(-10L)).isEqualTo("00:00")
        assertThat(TrafficFormatters.formatDuration(45L)).isEqualTo("00:45")
        assertThat(TrafficFormatters.formatDuration(125L)).isEqualTo("02:05")
        assertThat(TrafficFormatters.formatDuration(3665L)).isEqualTo("01:01:05")
    }
}

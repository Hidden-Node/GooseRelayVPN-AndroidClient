package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.util.VpnManager
import org.junit.After
import org.junit.Before
import org.junit.Test

class TrafficMonitorTest {

    @Before
    fun setUp() {
        VpnManager.stopTrafficMonitor()
    }

    @After
    fun tearDown() {
        VpnManager.stopTrafficMonitor()
    }

    @Test
    fun stopTrafficMonitor_resetsSpeedsToZero() {
        VpnManager.stopTrafficMonitor()
        assertThat(VpnManager.uploadSpeedBps.value).isEqualTo(0L)
        assertThat(VpnManager.downloadSpeedBps.value).isEqualTo(0L)
    }

    @Test
    fun computeTrafficDelta_calculatesPositiveDifference() {
        val delta = VpnManager.computeTrafficDelta(current = 1500L, previous = 1000L)
        assertThat(delta).isEqualTo(500L)
    }

    @Test
    fun computeTrafficDelta_returnsZeroWhenPreviousIsNegativeOrUninitialized() {
        val delta = VpnManager.computeTrafficDelta(current = 1500L, previous = -1L)
        assertThat(delta).isEqualTo(0L)
    }

    @Test
    fun computeTrafficDelta_returnsZeroWhenCurrentIsNegativeOrUnsupported() {
        val delta = VpnManager.computeTrafficDelta(current = -1L, previous = 1000L)
        assertThat(delta).isEqualTo(0L)
    }

    @Test
    fun computeTrafficDelta_returnsZeroOnCounterResetOrReboot() {
        val delta = VpnManager.computeTrafficDelta(current = 500L, previous = 1000L)
        assertThat(delta).isEqualTo(0L)
    }
}

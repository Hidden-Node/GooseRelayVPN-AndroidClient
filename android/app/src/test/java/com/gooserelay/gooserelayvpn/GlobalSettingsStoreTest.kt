package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.util.GlobalSettings
import com.gooserelay.gooserelayvpn.util.GlobalSettingsStore
import com.gooserelay.gooserelayvpn.util.SplitTunnelMode
import java.util.Locale
import org.junit.Test

class GlobalSettingsStoreTest {
    @Test
    fun defaultSettings_hasIncludeSplitTunnelMode() {
        val settings = GlobalSettings()
        assertThat(settings.splitTunnelMode).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(settings.splitTunnelingEnabled).isFalse()
    }

    @Test
    fun parseSplitTunnelMode_parsesValidModes() {
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("INCLUDE")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("EXCLUDE")).isEqualTo(SplitTunnelMode.EXCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("exclude")).isEqualTo(SplitTunnelMode.EXCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("include")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("  exclude  ")).isEqualTo(SplitTunnelMode.EXCLUDE)
    }

    @Test
    fun parseSplitTunnelMode_parsesCorrectlyUnderTurkishLocale() {
        val defaultLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            assertThat(GlobalSettingsStore.parseSplitTunnelMode("include")).isEqualTo(SplitTunnelMode.INCLUDE)
            assertThat(GlobalSettingsStore.parseSplitTunnelMode("exclude")).isEqualTo(SplitTunnelMode.EXCLUDE)
        } finally {
            Locale.setDefault(defaultLocale)
        }
    }

    @Test
    fun parseSplitTunnelMode_fallbackOnInvalidOrNull() {
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("UNKNOWN")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode("")).isEqualTo(SplitTunnelMode.INCLUDE)
        assertThat(GlobalSettingsStore.parseSplitTunnelMode(null)).isEqualTo(SplitTunnelMode.INCLUDE)
    }

    @Test
    fun store_exposesSaveAsyncMethod() {
        // Assert that the saveAsync API contract is present on the singleton.
        // Full behavioral DataStore tests require Android Context (instrumented tests).
        val method = GlobalSettingsStore::class.java.methods.find { it.name == "saveAsync" }
        assertThat(method).isNotNull()
    }
}

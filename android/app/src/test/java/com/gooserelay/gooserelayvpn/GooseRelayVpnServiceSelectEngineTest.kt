package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.GooseRelayVpnService
import com.gooserelay.gooserelayvpn.service.GooseRelayVpnService.Companion.Engine
import org.junit.Test

class GooseRelayVpnServiceSelectEngineTest {
    @Test
    fun selectEngine_pinsTruthTable() {
        assertThat(GooseRelayVpnService.selectEngine(false, true)).isEqualTo(Engine.BRIDGE)
        assertThat(GooseRelayVpnService.selectEngine(false, false)).isEqualTo(Engine.TUN2SOCKS)
        assertThat(GooseRelayVpnService.selectEngine(true, false)).isEqualTo(Engine.GVISOR)
        assertThat(GooseRelayVpnService.selectEngine(true, true)).isEqualTo(Engine.GVISOR)
    }
}

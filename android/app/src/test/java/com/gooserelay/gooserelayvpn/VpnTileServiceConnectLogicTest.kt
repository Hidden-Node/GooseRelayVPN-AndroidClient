package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.service.VpnTileService
import org.junit.Test

class VpnTileServiceConnectLogicTest {

    @Test
    fun `null profile returns OPEN_APP`() {
        val action = VpnTileService().tileActionForSelectedProfile(null)
        assertThat(action).isEqualTo(VpnTileService.TileAction.OPEN_APP)
    }

    @Test
    fun `non-null profile returns CONNECT`() {
        val profile = ProfileEntity(name = "test", tunnelKey = "x")
        val action = VpnTileService().tileActionForSelectedProfile(profile)
        assertThat(action).isEqualTo(VpnTileService.TileAction.CONNECT)
    }
}

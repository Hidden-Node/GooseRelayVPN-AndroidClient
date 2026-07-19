package com.gooserelay.gooserelayvpn.service

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import com.gooserelay.gooserelayvpn.MainActivity
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.AppDatabase
import com.gooserelay.gooserelayvpn.util.VpnManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@RequiresApi(Build.VERSION_CODES.N)
class VpnTileService : TileService() {

    private val tileScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val state = VpnManager.state.value
        when (state) {
            VpnManager.VpnState.CONNECTED, VpnManager.VpnState.CONNECTING -> VpnManager.disconnect(this)
            VpnManager.VpnState.DISCONNECTED -> connectFromTileIfReady()
            else -> Unit
        }
        updateTile()
    }

    override fun onDestroy() {
        tileScope.cancel()
        super.onDestroy()
    }

    private fun connectFromTileIfReady() {
        val prepareIntent = VpnService.prepare(this)
        if (prepareIntent != null) {
            startActivityAndCollapse(prepareIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }

        tileScope.launch {
            // Read the selected profile on a background thread — Room
            // call must not block the system's main thread or ANR the
            // quick-settings shade.
            val selectedProfile = withContext(Dispatchers.IO) {
                AppDatabase.getInstance(this@VpnTileService)
                    .profileDao()
                    .getSelectedProfile()
            }

            // If the tile service was torn down while we were reading
            // the DB (user dismissed the shade, or Android killed the
            // service), abandon the connect — tileScope is cancelled
            // in onDestroy, but cancellation is cooperative.
            if (!isActive) return@launch

            // Back on Dispatchers.Main (the scope's default dispatcher).
            if (selectedProfile != null) {
                VpnManager.connect(this@VpnTileService, selectedProfile)
                updateTile()
            } else {
                openApp()
            }
        }
    }

    /**
     * Test-only: returns the action the tile should take given the current
     * selected profile. Pure function — does not touch Android framework.
     */
    internal enum class TileAction { CONNECT, OPEN_APP }

    internal fun tileActionForSelectedProfile(selectedProfile: com.gooserelay.gooserelayvpn.data.local.ProfileEntity?): TileAction =
        if (selectedProfile != null) TileAction.CONNECT else TileAction.OPEN_APP

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivityAndCollapse(intent)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        when (VpnManager.state.value) {
            VpnManager.VpnState.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = "Connected"
            }
            VpnManager.VpnState.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = "Connecting..."
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.app_name)
                tile.subtitle = "Disconnected"
            }
        }
        tile.updateTile()
    }
}

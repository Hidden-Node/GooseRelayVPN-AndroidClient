package com.gooserelay.gooserelayvpn.util

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

enum class SplitTunnelMode { INCLUDE, EXCLUDE }

data class GlobalSettings(
    val connectionMode: String = "VPN",
    val allowLan: Boolean = false,
    val splitTunnelingEnabled: Boolean = false,
    val splitTunnelMode: SplitTunnelMode = SplitTunnelMode.INCLUDE,
    val splitPackagesCsv: String = "",
    val customDnsServers: String = "",
    val fakeDnsEnabled: Boolean = true,
    val internetSharingEnabled: Boolean = false,
    val internetSharingSocksPort: Int = 8090,
    val internetSharingHttpPort: Int = 8091,
    val internetSharingUser: String = "",
    val internetSharingPass: String = "",
)

object GlobalSettingsStore {
    private const val TAG = "GlobalSettingsStore"
    private val Context.dataStore by preferencesDataStore(name = "global_settings")

    private val KEY_CONNECTION_MODE = stringPreferencesKey("connection_mode")
    private val KEY_ALLOW_LAN = booleanPreferencesKey("allow_lan")
    private val KEY_SPLIT_TUNNELING_ENABLED = booleanPreferencesKey("split_tunneling_enabled")
    private val KEY_SPLIT_TUNNEL_MODE = stringPreferencesKey("split_tunnel_mode")
    private val KEY_SPLIT_PACKAGES = stringPreferencesKey("split_packages")
    private val KEY_CUSTOM_DNS_SERVERS = stringPreferencesKey("custom_dns_servers")
    private val KEY_FAKE_DNS_ENABLED = booleanPreferencesKey("fake_dns_enabled")
    private val KEY_INTERNET_SHARING_ENABLED = booleanPreferencesKey("internet_sharing_enabled")
    private val KEY_INTERNET_SHARING_SOCKS_PORT = stringPreferencesKey("internet_sharing_socks_port")
    private val KEY_INTERNET_SHARING_HTTP_PORT = stringPreferencesKey("internet_sharing_http_port")
    private val KEY_INTERNET_SHARING_USER = stringPreferencesKey("internet_sharing_user")
    private val KEY_INTERNET_SHARING_PASS = stringPreferencesKey("internet_sharing_pass")

    fun observe(context: Context): Flow<GlobalSettings> {
        return context.dataStore.data.map { prefs ->
            prefs.toModel()
        }
    }

    suspend fun load(context: Context): GlobalSettings {
        return context.dataStore.data.first().toModel().also { settings ->
            Log.d(TAG, "Loaded settings: connectionMode=${settings.connectionMode}, customDnsServers=${settings.customDnsServers}, fakeDnsEnabled=${settings.fakeDnsEnabled}")
        }
    }

    suspend fun save(context: Context, settings: GlobalSettings) {
        Log.d(TAG, "Saving settings: connectionMode=${settings.connectionMode}, customDnsServers=${settings.customDnsServers}, fakeDnsEnabled=${settings.fakeDnsEnabled}")
        context.dataStore.edit { prefs ->
            prefs[KEY_CONNECTION_MODE] = settings.connectionMode
            prefs[KEY_ALLOW_LAN] = settings.allowLan
            prefs[KEY_SPLIT_TUNNELING_ENABLED] = settings.splitTunnelingEnabled
            prefs[KEY_SPLIT_TUNNEL_MODE] = settings.splitTunnelMode.name
            prefs[KEY_SPLIT_PACKAGES] = settings.splitPackagesCsv
            prefs[KEY_CUSTOM_DNS_SERVERS] = settings.customDnsServers
            prefs[KEY_FAKE_DNS_ENABLED] = settings.fakeDnsEnabled
            prefs[KEY_INTERNET_SHARING_ENABLED] = settings.internetSharingEnabled
            prefs[KEY_INTERNET_SHARING_SOCKS_PORT] = settings.internetSharingSocksPort.toString()
            prefs[KEY_INTERNET_SHARING_HTTP_PORT] = settings.internetSharingHttpPort.toString()
            prefs[KEY_INTERNET_SHARING_USER] = settings.internetSharingUser
            prefs[KEY_INTERNET_SHARING_PASS] = settings.internetSharingPass
        }
        Log.d(TAG, "Settings saved successfully")
    }

    private fun Preferences.toModel(): GlobalSettings {
        return GlobalSettings(
            connectionMode = this[KEY_CONNECTION_MODE] ?: "VPN",
            allowLan = this[KEY_ALLOW_LAN] ?: false,
            splitTunnelingEnabled = this[KEY_SPLIT_TUNNELING_ENABLED] ?: false,
            splitTunnelMode = runCatching { SplitTunnelMode.valueOf(this[KEY_SPLIT_TUNNEL_MODE] ?: "INCLUDE") }.getOrDefault(SplitTunnelMode.INCLUDE),
            splitPackagesCsv = this[KEY_SPLIT_PACKAGES] ?: "",
            customDnsServers = this[KEY_CUSTOM_DNS_SERVERS] ?: "",
            fakeDnsEnabled = this[KEY_FAKE_DNS_ENABLED] ?: true,
            internetSharingEnabled = this[KEY_INTERNET_SHARING_ENABLED] ?: false,
            internetSharingSocksPort = this[KEY_INTERNET_SHARING_SOCKS_PORT]?.toIntOrNull() ?: 8090,
            internetSharingHttpPort = this[KEY_INTERNET_SHARING_HTTP_PORT]?.toIntOrNull() ?: 8091,
            internetSharingUser = this[KEY_INTERNET_SHARING_USER] ?: "",
            internetSharingPass = this[KEY_INTERNET_SHARING_PASS] ?: ""
        )
    }
}

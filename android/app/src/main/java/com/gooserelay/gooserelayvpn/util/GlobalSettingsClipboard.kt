package com.gooserelay.gooserelayvpn.util

import com.google.gson.JsonParser

/**
 * Parse text read from "Import from Clipboard".
 * Returns null when [raw] is blank, is not a JSON object, or is malformed.
 * Present fields are taken with type checks; absent or wrongly-typed fields fall back
 * to [GlobalSettings] defaults. Semantic cleanup (case, port ranges, CSV) is left to
 * the screen's existing normalize-on-save path.
 */
fun parseImportedSettings(raw: String): GlobalSettings? {
    if (raw.isBlank()) return null
    val root = runCatching { JsonParser.parseString(raw) }.getOrNull()
    if (root == null || !root.isJsonObject) return null
    val obj = root.asJsonObject
    val defaults = GlobalSettings()
    fun str(name: String, fallback: String): String =
        obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: fallback
    fun bool(name: String, fallback: Boolean): Boolean =
        obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isBoolean }?.asBoolean
            ?: fallback
    fun int(name: String, fallback: Int): Int =
        obj.get(name)?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }?.asInt
            ?: fallback
    return GlobalSettings(
        connectionMode = str("connectionMode", defaults.connectionMode),
        splitTunnelingEnabled = bool("splitTunnelingEnabled", defaults.splitTunnelingEnabled),
        splitTunnelMode = runCatching {
            SplitTunnelMode.valueOf(str("splitTunnelMode", defaults.splitTunnelMode.name).uppercase())
        }.getOrDefault(SplitTunnelMode.INCLUDE),
        splitPackagesCsv = str("splitPackagesCsv", defaults.splitPackagesCsv),
        customDnsServers = str("customDnsServers", defaults.customDnsServers),
        fakeDnsEnabled = bool("fakeDnsEnabled", defaults.fakeDnsEnabled),
        internetSharingEnabled = bool("internetSharingEnabled", defaults.internetSharingEnabled),
        internetSharingSocksPort = int("internetSharingSocksPort", defaults.internetSharingSocksPort),
        internetSharingHttpPort = int("internetSharingHttpPort", defaults.internetSharingHttpPort),
        internetSharingUser = str("internetSharingUser", defaults.internetSharingUser),
        internetSharingPass = str("internetSharingPass", defaults.internetSharingPass)
    )
}

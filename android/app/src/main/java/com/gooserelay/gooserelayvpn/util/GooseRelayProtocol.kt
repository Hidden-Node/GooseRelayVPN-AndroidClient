package com.gooserelay.gooserelayvpn.util

import android.util.Base64
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity

object GooseRelayProtocol {
    const val PREFIX = "goose-relay://"

    fun parse(raw: String): ProfileEntity? {
        val trimmed = raw.trim()
        if (!trimmed.startsWith(PREFIX)) return null
        return try {
            val base64 = trimmed.substring(PREFIX.length).trim()
            val json = String(Base64.decode(base64, Base64.DEFAULT), Charsets.UTF_8)
            ProfileJsonParser.parse(json)
        } catch (_: Exception) {
            null
        }
    }
}

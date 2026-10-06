package com.gooserelay.gooserelayvpn.util

/**
 * Pure-Kotlin gate deciding whether a VIEW/SEND file intent should trigger
 * a JSON profile import. Survives rotation via the saved-instance-state key.
 */
object ImportIntentGate {
    const val KEY_LAST_HANDLED_URI = "gooserelay.lastHandledImportUri"

    fun isJsonLike(mimeType: String?, uriString: String?): Boolean {
        if (uriString.isNullOrBlank()) return false
        if (mimeType != null && mimeType.contains("json", ignoreCase = true)) return true
        return uriString.lowercase().endsWith(".json")
    }

    fun shouldHandle(
        isFirstCreation: Boolean,
        uriToken: String?,
        lastHandledUriToken: String?
    ): Boolean {
        if (!isFirstCreation) return false
        if (uriToken.isNullOrBlank()) return false
        return uriToken != lastHandledUriToken
    }
}

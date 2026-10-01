package com.gooserelay.gooserelayvpn.service

internal fun formatSharingRejectDiagnostic(active: Int, oldestAgeMs: Long, suppressed: Int): String {
    val age = if (oldestAgeMs < 0) "unknown" else "${oldestAgeMs}ms"
    val tail = if (suppressed > 0) " ($suppressed similar rejects suppressed)" else ""
    return "Sharing connection limit reached; rejecting client (active=$active, oldest=$age)$tail"
}

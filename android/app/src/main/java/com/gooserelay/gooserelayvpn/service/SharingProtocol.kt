package com.gooserelay.gooserelayvpn.service

import android.util.Base64

internal fun decodeBase64(value: String): ByteArray? =
    runCatching { Base64.decode(value, Base64.DEFAULT) }.getOrNull()

internal fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
    if (a.size != b.size) return false
    return java.security.MessageDigest.isEqual(a, b)
}

internal fun readFully(input: java.io.InputStream, buffer: ByteArray, offset: Int, length: Int) {
    var total = 0
    while (total < length) {
        val read = input.read(buffer, offset + total, length - total)
        if (read < 0) throw IllegalStateException("Unexpected EOF while reading SOCKS5 response")
        total += read
    }
}

/**
 * Validates a Proxy-Authorization header against expected credentials.
 * Blank-blank credentials mean the proxy is open (always true) — both-or-
 * neither is enforced at the UI.
 */
internal fun isValidBasicProxyAuth(
    header: String?,
    username: String,
    password: String,
    decoder: (String) -> ByteArray? = ::decodeBase64
): Boolean {
    if (username.isBlank() && password.isBlank()) return true
    val value = header?.trim().orEmpty()
    if (!value.startsWith("Basic ", ignoreCase = true)) return false
    val encoded = value.substringAfter(" ", "").trim()
    if (encoded.isBlank()) return false
    val decoded = decoder(encoded) ?: return false
    return constantTimeEquals(decoded, "$username:$password".toByteArray(Charsets.UTF_8))
}

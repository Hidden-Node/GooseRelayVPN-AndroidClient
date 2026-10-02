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

internal fun createSocks5Tunnel(socksPort: Int, targetHost: String, targetPort: Int, coreUser: String? = null, corePass: String? = null): java.net.Socket {
    val socket = java.net.Socket("127.0.0.1", socksPort)
    try {
        socket.soTimeout = 15000
        val input = socket.getInputStream()
        val output = socket.getOutputStream()

        if (!coreUser.isNullOrBlank() && !corePass.isNullOrBlank()) {
            output.write(byteArrayOf(0x05, 0x02, 0x00, 0x02))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting, 0, greeting.size)
            if (greeting[0] != 0x05.toByte()) {
                throw IllegalStateException("SOCKS5 upstream greeting failed")
            }
            when (greeting[1].toInt() and 0xFF) {
                0x00 -> { /* no auth selected: proceed */ }
                0x02 -> {
                    val userBytes = coreUser.toByteArray(Charsets.UTF_8)
                    val passBytes = corePass.toByteArray(Charsets.UTF_8)
                    if (userBytes.size > 255 || passBytes.size > 255) {
                        throw IllegalStateException("SOCKS5 upstream credentials too long")
                    }
                    val auth = ByteArray(3 + userBytes.size + passBytes.size)
                    auth[0] = 0x01
                    auth[1] = userBytes.size.toByte()
                    System.arraycopy(userBytes, 0, auth, 2, userBytes.size)
                    auth[2 + userBytes.size] = passBytes.size.toByte()
                    System.arraycopy(passBytes, 0, auth, 3 + userBytes.size, passBytes.size)
                    output.write(auth)
                    output.flush()
                    val status = ByteArray(2)
                    readFully(input, status, 0, status.size)
                    if (status[0] != 0x01.toByte() || status[1] != 0x00.toByte()) {
                        throw IllegalStateException("SOCKS5 upstream auth failed")
                    }
                }
                else -> throw IllegalStateException("SOCKS5 upstream greeting failed")
            }
        } else {
            output.write(byteArrayOf(0x05, 0x01, 0x00))
            output.flush()
            val greeting = ByteArray(2)
            readFully(input, greeting, 0, greeting.size)
            if (greeting[0] != 0x05.toByte() || greeting[1] != 0x00.toByte()) {
                throw IllegalStateException("SOCKS5 upstream greeting failed")
            }
        }

        val hostBytes = targetHost.toByteArray(Charsets.UTF_8)
        if (hostBytes.size > 255) {
            throw IllegalArgumentException("Target host is too long")
        }
        val req = ByteArray(7 + hostBytes.size)
        req[0] = 0x05
        req[1] = 0x01
        req[2] = 0x00
        req[3] = 0x03
        req[4] = hostBytes.size.toByte()
        System.arraycopy(hostBytes, 0, req, 5, hostBytes.size)
        req[5 + hostBytes.size] = ((targetPort shr 8) and 0xFF).toByte()
        req[6 + hostBytes.size] = (targetPort and 0xFF).toByte()
        output.write(req)
        output.flush()

        val header = ByteArray(4)
        readFully(input, header, 0, header.size)
        if (header[0] != 0x05.toByte() || header[1] != 0x00.toByte()) {
            throw IllegalStateException("SOCKS5 connect failed with code ${header[1].toInt() and 0xFF}")
        }

        val addrLen = when (header[3].toInt() and 0xFF) {
            0x01 -> 4
            0x03 -> {
                val size = input.read()
                if (size < 0) throw IllegalStateException("SOCKS5 malformed bind address length")
                size
            }
            0x04 -> 16
            else -> throw IllegalStateException("SOCKS5 unsupported bind address type")
        }
        val skip = ByteArray(addrLen + 2)
        readFully(input, skip, 0, skip.size)
        return socket
    } catch (e: Exception) {
        runCatching { socket.close() }
        throw e
    }
}

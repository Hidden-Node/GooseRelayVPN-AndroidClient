package com.gooserelay.gooserelayvpn.service

internal class LineTooLongException : IllegalStateException("HTTP line exceeds 16384 bytes")

internal fun readLineUnbuffered(input: java.io.InputStream): String? {
    val bytes = java.io.ByteArrayOutputStream(256)
    while (true) {
        val next = input.read()
        if (next < 0) {
            if (bytes.size() == 0) return null
            break
        }
        if (next == '\n'.code) break
        if (next != '\r'.code) {
            bytes.write(next)
            if (bytes.size() > 16384) throw LineTooLongException()
        }
    }
    return String(bytes.toByteArray(), Charsets.ISO_8859_1)
}

package com.gooserelay.gooserelayvpn.service

import com.gooserelay.gooserelayvpn.util.VpnManager
import kotlinx.coroutines.*

internal object SharingServer {

    private const val PUMP_BUFFER_SIZE = 32 * 1024

    internal suspend fun handleSharingSocksClient(client: java.net.Socket, coreSocksPort: Int, username: String, password: String, coreSocksUser: String? = null, coreSocksPass: String? = null) {
        var upstream: java.net.Socket? = null
        try {
            client.soTimeout = 15000
            val input = client.getInputStream()
            val output = client.getOutputStream()

            val authRequired = username.isNotBlank() && password.isNotBlank()

            // --- SOCKS5 greeting (RFC 1928) ---
            val header = ByteArray(2)
            readFully(input, header, 0, 2)
            if (header[0] != 0x05.toByte()) return
            val nMethods = header[1].toInt() and 0xFF
            if (nMethods == 0) return
            val methods = ByteArray(nMethods)
            readFully(input, methods, 0, nMethods)

            if (authRequired) {
                if (!methods.any { it == 0x02.toByte() }) {
                    output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush(); return
                }
                output.write(byteArrayOf(0x05, 0x02)); output.flush()
                // --- RFC 1929 user/pass sub-negotiation ---
                val subVersion = input.read()
                if (subVersion != 0x01) { output.write(byteArrayOf(0x01, 0x01)); output.flush(); return }
                val ulen = input.read()
                if (ulen < 0) return
                val ub = ByteArray(ulen)
                readFully(input, ub, 0, ulen)
                val plen = input.read()
                if (plen < 0) return
                val pb = ByteArray(plen)
                readFully(input, pb, 0, plen)
                val ok = constantTimeEquals(ub, username.toByteArray(Charsets.UTF_8)) &&
                    constantTimeEquals(pb, password.toByteArray(Charsets.UTF_8))
                output.write(byteArrayOf(0x01, if (ok) 0x00 else 0x01))
                output.flush()
                if (!ok) return
            } else {
                output.write(byteArrayOf(0x05, 0x00)); output.flush()
            }

            // --- SOCKS5 request ---
            val req = ByteArray(4)
            readFully(input, req, 0, 4)
            if (req[0] != 0x05.toByte()) return
            if (req[1] != 0x01.toByte()) {
                // 0x07 = command not supported
                output.write(byteArrayOf(0x05, 0x07, 0x00)); output.flush(); return
            }
            val host = when (req[3].toInt() and 0xFF) {
                0x01 -> { val b = ByteArray(4); readFully(input, b, 0, 4); b.joinToString(".") { (it.toInt() and 0xFF).toString() } }
                0x03 -> { val l = input.read(); if (l < 0) return; val b = ByteArray(l); readFully(input, b, 0, l); String(b, Charsets.UTF_8) }
                0x04 -> { val b = ByteArray(16); readFully(input, b, 0, 16); java.net.InetAddress.getByAddress(b).hostAddress ?: return }
                else -> { output.write(byteArrayOf(0x05, 0x08, 0x00)); output.flush(); return }
            }
            val portBytes = ByteArray(2); readFully(input, portBytes, 0, 2)
            val port = ((portBytes[0].toInt() and 0xFF) shl 8) or (portBytes[1].toInt() and 0xFF)

            upstream = try { createSocks5Tunnel(coreSocksPort, host, port, coreSocksUser, coreSocksPass) } catch (e: Exception) {
                VpnManager.appendLog("Sharing SOCKS5 upstream to $host:$port failed: ${e.message}")
                output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
                return
            }
            // Handshake is done: idle timeouts off. A quiet tunnel (SSH,
            // WebSocket) must not be killed by a read timeout.
            upstream.soTimeout = 0
            client.soTimeout = 0
            upstream.keepAlive = true
            client.keepAlive = true
            // 0x05 0x00 0x00 0x01 + 4-byte bind addr + 2-byte bind port
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()

            bridgeBidirectional(client, upstream)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            VpnManager.appendLog("Sharing SOCKS5 client error: ${e.message}")
        } finally {
            runCatching { upstream?.close() }
            runCatching { client.close() }
        }
    }

    internal suspend fun handleHttpProxyClient(client: java.net.Socket, upstreamSocksPort: Int, username: String, password: String, coreSocksUser: String? = null, coreSocksPass: String? = null) {
        try {
            client.soTimeout = 15000
            // Buffered: coalesces per-byte read() syscalls. The SAME wrapper MUST reach the client->upstream pump: bytes already read past the headers belong to the tunnel/body, not to us.
            val input = java.io.BufferedInputStream(client.getInputStream())
            val output = client.getOutputStream().bufferedWriter()

            val requestLine = readLineUnbuffered(input) ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) {
                client.close()
                return
            }

            val method = parts[0]
            val url = parts[1]

            var authHeader: String? = null
            val headerLines = ArrayList<String>()
            while (true) {
                val line = readLineUnbuffered(input) ?: break
                if (line.isBlank()) break
                headerLines.add(line)
                if (headerLines.size > 100) {
                    output.write("HTTP/1.1 431 Request Header Fields Too Large\r\nConnection: close\r\n\r\n"); output.flush()
                    return
                }
                val idx = line.indexOf(':')
                if (idx <= 0) continue
                val name = line.substring(0, idx).trim()
                val value = line.substring(idx + 1).trim()
                if (name.equals("Proxy-Authorization", ignoreCase = true)) {
                    authHeader = value
                }
            }

            val requiresAuth = username.isNotBlank() && password.isNotBlank()
            if (requiresAuth && !isValidBasicProxyAuth(authHeader, username, password)) {
                output.write(
                    "HTTP/1.1 407 Proxy Authentication Required\r\n" +
                        "Proxy-Authenticate: Basic realm=\"GooseRelayVPN\"\r\n" +
                        "Connection: close\r\n\r\n"
                )
                output.flush()
                return
            }

            if (method.equals("CONNECT", ignoreCase = true)) {
                val target = parseProxyTarget("CONNECT", url)
                if (target == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\n\r\n"); output.flush()
                    return
                }
                val upstream = try {
                    createSocks5Tunnel(upstreamSocksPort, target.host, target.port, coreSocksUser, coreSocksPass)
                } catch (e: Exception) {
                    VpnManager.appendLog("Sharing HTTP CONNECT to ${target.host}:${target.port} failed: ${e.message}")
                    output.write("HTTP/1.1 502 Bad Gateway\r\n\r\n"); output.flush()
                    return
                }
                upstream.soTimeout = 0
                client.soTimeout = 0
                upstream.keepAlive = true
                client.keepAlive = true
                output.write("HTTP/1.1 200 Connection Established\r\n\r\n")
                output.flush()
                bridgeBidirectional(client, upstream, input)
            } else {
                val target = parseProxyTarget(method, url)
                if (target == null) {
                    output.write("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n"); output.flush()
                    return
                }
                val upstream = try {
                    createSocks5Tunnel(upstreamSocksPort, target.host, target.port, coreSocksUser, coreSocksPass)
                } catch (e: Exception) {
                    VpnManager.appendLog("Sharing HTTP $method to ${target.host}:${target.port} failed: ${e.message}")
                    output.write("HTTP/1.1 502 Bad Gateway\r\nConnection: close\r\n\r\n"); output.flush()
                    return
                }
                upstream.soTimeout = 0
                client.soTimeout = 0
                upstream.keepAlive = true
                client.keepAlive = true
                // Re-emit the request with a relative path (origin-form) to
                // the tunnel, then bridge; the tunnel's SOCKS5 target is
                // already resolved by createSocks5Tunnel.
                val forwardedHeaders = headerLines
                    .filter { line ->
                        val idx = line.indexOf(':')
                        if (idx <= 0) return@filter true
                        val name = line.substring(0, idx).trim()
                        !name.equals("Host", ignoreCase = true) &&
                            !name.equals("Proxy-Authorization", ignoreCase = true) &&
                            !name.equals("Connection", ignoreCase = true) &&
                            !name.equals("Proxy-Connection", ignoreCase = true)
                    }
                    .joinToString("") { "$it\r\n" }
                val rewritten = buildString {
                    append(method).append(' ').append(target.path).append(" HTTP/1.1\r\n")
                    append("Host: ").append(target.host)
                    if (target.port != 80) append(':').append(target.port)
                    append("\r\n")
                    // Absolute-form is bridged once per connection: force
                    // close so a keep-alive second request never lands on
                    // the wrong upstream.
                    append("Connection: close\r\n")
                    append(forwardedHeaders)
                    append("\r\n")
                }
                val upstreamOut = upstream.getOutputStream()
                upstreamOut.write(rewritten.toByteArray(Charsets.ISO_8859_1))
                upstreamOut.flush()
                bridgeBidirectional(client, upstream, input)
            }
} catch (_: Exception) {} finally {
        runCatching { client.close() }
    }
    }

    private suspend fun bridgeBidirectional(client: java.net.Socket, upstream: java.net.Socket, clientInput: java.io.InputStream? = null) = coroutineScope {
        val upToClient = launch(Dispatchers.IO) {
            val buffer = ByteArray(PUMP_BUFFER_SIZE)
            try {
                val input = upstream.getInputStream()
                val output = client.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            } catch (_: Exception) {
            } finally {
                // Close both sockets: closing unblocks the peer pump's
                // blocking read so the bridge drains instead of waiting
                // for BOTH directions to finish on its own.
                runCatching { upstream.close() }
                runCatching { client.close() }
            }
        }

        val clientToUp = launch(Dispatchers.IO) {
            val buffer = ByteArray(PUMP_BUFFER_SIZE)
            try {
                val input = clientInput ?: client.getInputStream()
                val output = upstream.getOutputStream()
                while (isActive && !client.isClosed && !upstream.isClosed) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    output.write(buffer, 0, read)
                }
            } catch (_: Exception) {
            } finally {
                // Close both sockets: closing unblocks the peer pump's
                // blocking read so the bridge drains instead of waiting
                // for BOTH directions to finish on its own.
                runCatching { upstream.close() }
                runCatching { client.close() }
            }
        }

        joinAll(upToClient, clientToUp)
        runCatching { upstream.close() }
        runCatching { client.close() }
    }
}

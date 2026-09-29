package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.SharingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket

class SharingServerHandoffTest {

    private fun readExactly(input: InputStream, n: Int): ByteArray {
        val buf = ByteArray(n)
        var total = 0
        while (total < n) {
            val r = input.read(buf, total, n - total)
            if (r < 0) throw IllegalStateException("stub EOF")
            total += r
        }
        return buf
    }

    /**
     * Runs [script] against one accepted loopback connection on a daemon thread.
     * Returns (port, thread). Always join with timeout + assert not-alive:
     * a stuck stub must fail the test, never hang the suite.
     */
    private fun runStub(script: (input: InputStream, output: OutputStream) -> Unit): Pair<Int, Thread> {
        val server = java.net.ServerSocket(0)
        val t = Thread {
            server.use { srv ->
                srv.soTimeout = 5000
                val conn = runCatching { srv.accept() }.getOrNull() ?: return@Thread
                conn.use { c ->
                    c.soTimeout = 3000
                    runCatching { script(c.getInputStream(), c.getOutputStream()) }
                }
            }
        }
        t.isDaemon = true
        t.start()
        return server.localPort to t
    }

    private fun joinStub(t: Thread) {
        t.join(5000)
        assertThat(t.isAlive).isFalse()
    }

    private fun readHttpHeaders(input: InputStream): String {
        val buf = ByteArrayOutputStream()
        val last = ByteArray(4)
        var count = 0
        while (true) {
            val b = input.read()
            if (b < 0) throw IllegalStateException("EOF in headers")
            buf.write(b)
            if (count < 4) {
                last[count] = b.toByte()
                count++
            } else {
                last[0] = last[1]
                last[1] = last[2]
                last[2] = last[3]
                last[3] = b.toByte()
            }
            if (count == 4 &&
                last[0] == '\r'.code.toByte() &&
                last[1] == '\n'.code.toByte() &&
                last[2] == '\r'.code.toByte() &&
                last[3] == '\n'.code.toByte()
            ) break
        }
        return buf.toString(Charsets.ISO_8859_1.name())
    }

    @Test
    fun `httpConnect_coalescedClientHello_reachesUpstream`() = runBlocking {
        withTimeout(10000) {
            val sentinel = ByteArray(64) { i -> (i * 31 + 7).toByte() }
            var upstreamGot = ByteArray(0)
            val (stubPort, stubThread) = runStub { input, output ->
                readExactly(input, 3)
                output.write(byteArrayOf(0x05, 0x00)); output.flush()
                readExactly(input, 4 + 1 + "example.com".length + 2)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
                upstreamGot = readExactly(input, 64)
            }
            val server = ServerSocket(0)
            val client = Socket("127.0.0.1", server.localPort)
            client.soTimeout = 8000
            val accepted = server.accept()
            val handlerJob = launch(Dispatchers.IO) {
                SharingServer.handleHttpProxyClient(accepted, stubPort, "", "")
            }
            try {
                val head =
                    "CONNECT example.com:443 HTTP/1.1\r\nHost: example.com:443\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                val request = ByteArray(head.size + sentinel.size)
                System.arraycopy(head, 0, request, 0, head.size)
                System.arraycopy(sentinel, 0, request, head.size, sentinel.size)
                client.getOutputStream().write(request)
                client.getOutputStream().flush()
                val reply = readHttpHeaders(client.getInputStream())
                assertThat(reply).startsWith("HTTP/1.1 200")
                client.shutdownOutput()
                handlerJob.join()
                joinStub(stubThread)
                assertThat(upstreamGot).isEqualTo(sentinel)
            } finally {
                runCatching { client.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }
        }
    }

    @Test
    fun `httpPost_coalescedBody_forwardedIntact`() = runBlocking {
        withTimeout(10000) {
            val body = ByteArray(64) { i -> (i * 17 + 5).toByte() }
            var upstreamBody = ByteArray(0)
            var upstreamHeaders = ""
            val (stubPort, stubThread) = runStub { input, output ->
                readExactly(input, 3)
                output.write(byteArrayOf(0x05, 0x00)); output.flush()
                readExactly(input, 4 + 1 + "example.com".length + 2)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
                upstreamHeaders = readHttpHeaders(input)
                upstreamBody = readExactly(input, 64)
                output.write("HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n".toByteArray(Charsets.ISO_8859_1))
                output.flush()
            }
            val server = ServerSocket(0)
            val client = Socket("127.0.0.1", server.localPort)
            client.soTimeout = 8000
            val accepted = server.accept()
            val handlerJob = launch(Dispatchers.IO) {
                SharingServer.handleHttpProxyClient(accepted, stubPort, "", "")
            }
            try {
                val head =
                    ("POST http://example.com:8080/submit HTTP/1.1\r\n" +
                        "Host: example.com:8080\r\n" +
                        "Content-Length: 64\r\n\r\n")
                        .toByteArray(Charsets.ISO_8859_1)
                val request = ByteArray(head.size + body.size)
                System.arraycopy(head, 0, request, 0, head.size)
                System.arraycopy(body, 0, request, head.size, body.size)
                client.getOutputStream().write(request)
                client.getOutputStream().flush()
                val reply = readHttpHeaders(client.getInputStream())
                assertThat(reply).startsWith("HTTP/1.1 200")
                client.shutdownOutput()
                handlerJob.join()
                joinStub(stubThread)
                assertThat(upstreamHeaders).startsWith("POST /submit HTTP/1.1")
                assertThat(upstreamHeaders).contains("Content-Length: 64")
                assertThat(upstreamBody).isEqualTo(body)
            } finally {
                runCatching { client.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }
        }
    }
}

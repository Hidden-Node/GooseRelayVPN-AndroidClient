package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.SharingServer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class SharingHttpUpstreamCloseTest {

    /**
     * Socket wrapper whose output stream always throws while its input stream
     * delegates to a real connected socket. Lets the handler parse a valid
     * CONNECT request and create its upstream tunnel, then fail
     * deterministically on the `200` reply write.
     */
    private class WriteFailSocket(private val delegate: Socket) : Socket() {
        override fun getInputStream(): InputStream = delegate.getInputStream()
        override fun getOutputStream(): OutputStream =
            object : OutputStream() {
                override fun write(b: Int): Unit = throw IOException("boom")
            }
        override fun setSoTimeout(timeout: Int) {
            delegate.soTimeout = timeout
        }
        override fun getSoTimeout(): Int = delegate.soTimeout
        override fun setKeepAlive(on: Boolean) {
            delegate.keepAlive = on
        }
        override fun getKeepAlive(): Boolean = delegate.keepAlive
        override fun close() {
            runCatching { super.close() }
            runCatching { delegate.close() }
        }
        override fun isClosed(): Boolean = delegate.isClosed
        override fun isConnected(): Boolean = delegate.isConnected
    }

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
    fun `failed 200-write closes upstream tunnel`() = runBlocking {
        withTimeout(15000) {
            val eofSeen = AtomicInteger(-2)
            val (stubPort, stubThread) = runStub { input, output ->
                readExactly(input, 3)
                output.write(byteArrayOf(0x05, 0x00)); output.flush()
                readExactly(input, 4 + 1 + "example.com".length + 2)
                output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
                eofSeen.set(input.read()) // -1 iff the handler closed the tunnel
            }
            val server = ServerSocket(0)
            val client = Socket("127.0.0.1", server.localPort)
            client.soTimeout = 8000
            val accepted = server.accept()
            val failing = WriteFailSocket(accepted)
            val handlerJob = launch(Dispatchers.IO) {
                SharingServer.handleHttpProxyClient(failing, stubPort, "", "")
            }
            try {
                val request =
                    "CONNECT example.com:80 HTTP/1.1\r\nHost: example.com:80\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                client.getOutputStream().write(request)
                client.getOutputStream().flush()
                handlerJob.join()
                joinStub(stubThread)
                assertThat(eofSeen.get()).isEqualTo(-1)
            } finally {
                runCatching { client.close() }
                runCatching { failing.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }
        }
    }

    @Test
    fun `tunnel-create failure closes nothing and returns`() = runBlocking {
        withTimeout(15000) {
            val closed = ServerSocket(0)
            val deadPort = closed.localPort
            closed.close()
            val server = ServerSocket(0)
            val client = Socket("127.0.0.1", server.localPort)
            client.soTimeout = 8000
            val accepted = server.accept()
            val handlerJob = launch(Dispatchers.IO) {
                SharingServer.handleHttpProxyClient(accepted, deadPort, "", "")
            }
            try {
                val request =
                    "CONNECT example.com:80 HTTP/1.1\r\nHost: example.com:80\r\n\r\n"
                        .toByteArray(Charsets.ISO_8859_1)
                client.getOutputStream().write(request)
                client.getOutputStream().flush()
                val reply = readHttpHeaders(client.getInputStream())
                assertThat(reply).startsWith("HTTP/1.1 502")
                handlerJob.join()
                assertThat(handlerJob.isCompleted).isTrue()
            } finally {
                runCatching { client.close() }
                runCatching { accepted.close() }
                runCatching { server.close() }
            }
        }
    }
}

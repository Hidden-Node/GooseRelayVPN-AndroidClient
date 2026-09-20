package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.createSocks5Tunnel
import org.junit.Test
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

class SharingTunnelTest {

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

    @Test
    fun `bad greeting throws and client socket is closed`() {
        val eofSeen = AtomicInteger(-2)
        val (port, t) = runStub { input, output ->
            readExactly(input, 3)
            output.write(byteArrayOf(0x05, 0xFF.toByte())); output.flush()
            eofSeen.set(input.read()) // -1 iff the tunnel closed its socket after throwing
        }
        val thrown = runCatching { createSocks5Tunnel(port, "example.com", 80) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        assertThat(thrown.exceptionOrNull()).hasMessageThat().contains("greeting")
        joinStub(t)
        assertThat(eofSeen.get()).isEqualTo(-1)
    }

    @Test
    fun `non-zero connect reply throws and client socket is closed`() {
        val eofSeen = AtomicInteger(-2)
        val (port, t) = runStub { input, output ->
            readExactly(input, 3)
            output.write(byteArrayOf(0x05, 0x00)); output.flush()
            readExactly(input, 4 + 1 + "example.com".length + 2)
            output.write(byteArrayOf(0x05, 0x01, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
            eofSeen.set(input.read())
        }
        val thrown = runCatching { createSocks5Tunnel(port, "example.com", 80) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        joinStub(t)
        assertThat(eofSeen.get()).isEqualTo(-1)
    }

    @Test
    fun `unsupported bind address type throws and client socket is closed`() {
        val eofSeen = AtomicInteger(-2)
        val (port, t) = runStub { input, output ->
            readExactly(input, 3)
            output.write(byteArrayOf(0x05, 0x00)); output.flush()
            readExactly(input, 4 + 1 + "example.com".length + 2)
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x09, 0, 0)); output.flush()
            eofSeen.set(input.read())
        }
        val thrown = runCatching { createSocks5Tunnel(port, "example.com", 80) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        joinStub(t)
        assertThat(eofSeen.get()).isEqualTo(-1)
    }

    @Test
    fun `immediate server close throws IllegalStateException`() {
        val (port, t) = runStub { _, _ -> /* accept then close: client readFully hits EOF */ }
        val thrown = runCatching { createSocks5Tunnel(port, "example.com", 80) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
        joinStub(t)
    }

    @Test
    fun `happy path returns a connected socket`() {
        val (port, t) = runStub { input, output ->
            readExactly(input, 3)
            output.write(byteArrayOf(0x05, 0x00)); output.flush()
            readExactly(input, 4 + 1 + "example.com".length + 2)
            output.write(byteArrayOf(0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0)); output.flush()
            readExactly(input, 1) // hold until the test closes the socket -> stub EOF, thread exits
        }
        val s = createSocks5Tunnel(port, "example.com", 80)
        assertThat(s.isConnected).isTrue()
        assertThat(s.isClosed).isFalse()
        runCatching { s.close() }
        joinStub(t)
    }
}

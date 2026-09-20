package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.LineTooLongException
import com.gooserelay.gooserelayvpn.service.readLineUnbuffered
import org.junit.Test
import java.io.ByteArrayInputStream

class ReadLineUnbufferedCapTest {

    @Test
    fun `normal line is read`() {
        val input = ByteArrayInputStream("GET / HTTP/1.1\r\n".toByteArray())
        assertThat(readLineUnbuffered(input)).isEqualTo("GET / HTTP/1.1")
    }

    @Test
    fun `empty stream returns null`() {
        val input = ByteArrayInputStream(ByteArray(0))
        assertThat(readLineUnbuffered(input)).isNull()
    }

    @Test
    fun `line over 16384 bytes throws`() {
        val big = "A".repeat(20000) + "\n"
        val input = ByteArrayInputStream(big.toByteArray(Charsets.ISO_8859_1))
        val thrown = runCatching { readLineUnbuffered(input) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(LineTooLongException::class.java)
    }
}

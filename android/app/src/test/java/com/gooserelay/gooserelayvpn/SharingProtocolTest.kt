package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.constantTimeEquals
import com.gooserelay.gooserelayvpn.service.isValidBasicProxyAuth
import com.gooserelay.gooserelayvpn.service.readFully
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class SharingProtocolTest {

    private fun jvmDecode(value: String): ByteArray? = runCatching {
        Base64.getDecoder().decode(value)
    }.getOrNull()

    // --- constantTimeEquals ---

    @Test
    fun `equal arrays compare true`() {
        assertThat(constantTimeEquals("abc".toByteArray(), "abc".toByteArray())).isTrue()
    }

    @Test
    fun `different content compares false`() {
        assertThat(constantTimeEquals("abc".toByteArray(), "abd".toByteArray())).isFalse()
    }

    @Test
    fun `different lengths compare false`() {
        assertThat(constantTimeEquals("abc".toByteArray(), "abcd".toByteArray())).isFalse()
    }

    @Test
    fun `empty arrays compare true`() {
        assertThat(constantTimeEquals(ByteArray(0), ByteArray(0))).isTrue()
    }

    // --- isValidBasicProxyAuth ---

    @Test
    fun `open proxy when both credentials blank`() {
        assertThat(isValidBasicProxyAuth(null, "", "")).isTrue()
        assertThat(isValidBasicProxyAuth("garbage", "", "")).isTrue()
    }

    @Test
    fun `valid basic header passes`() {
        val header = "Basic " + Base64.getEncoder()
            .encodeToString("user:pass".toByteArray())
        assertThat(isValidBasicProxyAuth(header, "user", "pass", ::jvmDecode)).isTrue()
    }

    @Test
    fun `wrong password fails`() {
        val header = "Basic " + Base64.getEncoder()
            .encodeToString("user:wrong".toByteArray())
        assertThat(isValidBasicProxyAuth(header, "user", "pass", ::jvmDecode)).isFalse()
    }

    @Test
    fun `wrong username fails`() {
        val header = "Basic " + Base64.getEncoder()
            .encodeToString("other:pass".toByteArray())
        assertThat(isValidBasicProxyAuth(header, "user", "pass", ::jvmDecode)).isFalse()
    }

    @Test
    fun `missing header fails when auth required`() {
        assertThat(isValidBasicProxyAuth(null, "user", "pass", ::jvmDecode)).isFalse()
    }

    @Test
    fun `non-basic scheme fails`() {
        assertThat(isValidBasicProxyAuth("Bearer xyz", "user", "pass", ::jvmDecode)).isFalse()
    }

    @Test
    fun `malformed base64 fails without throwing`() {
        assertThat(isValidBasicProxyAuth("Basic !!!not-base64!!!", "user", "pass", ::jvmDecode)).isFalse()
    }

    @Test
    fun `credential containing colon splits on first colon`() {
        // "u:p:a" decodes with username "u" and password "p:a"
        val header = "Basic " + Base64.getEncoder()
            .encodeToString("u:p:a".toByteArray())
        assertThat(isValidBasicProxyAuth(header, "u", "p:a", ::jvmDecode)).isTrue()
    }

    // --- readFully ---

    @Test
    fun `readFully reads exactly length bytes`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3, 4, 5))
        val buf = ByteArray(5)
        readFully(input, buf, 0, 5)
        assertThat(buf).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
    }

    @Test
    fun `readFully with offset respects offset`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2, 3))
        val buf = ByteArray(5)
        readFully(input, buf, 2, 3)
        assertThat(buf[2]).isEqualTo(1.toByte())
        assertThat(buf[4]).isEqualTo(3.toByte())
    }

    @Test
    fun `readFully on premature EOF throws IllegalStateException`() {
        val input = ByteArrayInputStream(byteArrayOf(1, 2))
        val thrown = runCatching { readFully(input, ByteArray(5), 0, 5) }
        assertThat(thrown.exceptionOrNull()).isInstanceOf(IllegalStateException::class.java)
    }
}

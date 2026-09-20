package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.parseProxyTarget
import org.junit.Test

class SharingHttpParserTest {

    @Test
    fun `CONNECT host port`() {
        val t = parseProxyTarget("CONNECT", "example.com:443")!!
        assertThat(t.host).isEqualTo("example.com")
        assertThat(t.port).isEqualTo(443)
    }

    @Test
    fun `CONNECT bare host defaults to 443`() {
        assertThat(parseProxyTarget("CONNECT", "example.com")!!.port).isEqualTo(443)
    }

    @Test
    fun `CONNECT ipv6 literal parses`() {
        val t = parseProxyTarget("CONNECT", "[::1]:8443")!!
        assertThat(t.host).isEqualTo("::1")
        assertThat(t.port).isEqualTo(8443)
    }

    @Test
    fun `CONNECT ipv6 literal without port`() {
        val t = parseProxyTarget("CONNECT", "[2001:db8::1]")!!
        assertThat(t.host).isEqualTo("2001:db8::1")
        assertThat(t.port).isEqualTo(443)
    }

    @Test
    fun `absolute form GET parses`() {
        val t = parseProxyTarget("GET", "http://example.com:8080/a/b?c=1")!!
        assertThat(t.host).isEqualTo("example.com")
        assertThat(t.port).isEqualTo(8080)
        assertThat(t.path).isEqualTo("/a/b?c=1")
    }

    @Test
    fun `absolute form without path yields root`() {
        val t = parseProxyTarget("GET", "http://connectivitycheck.gstatic.com/generate_204")!!
        assertThat(t.port).isEqualTo(80)
        assertThat(t.path).isEqualTo("/generate_204")
    }

    @Test
    fun `https absolute form is rejected`() {
        assertThat(parseProxyTarget("GET", "https://example.com/")).isNull()
    }

    @Test
    fun `relative form GET is rejected`() {
        assertThat(parseProxyTarget("GET", "/generate_204")).isNull()
    }
}

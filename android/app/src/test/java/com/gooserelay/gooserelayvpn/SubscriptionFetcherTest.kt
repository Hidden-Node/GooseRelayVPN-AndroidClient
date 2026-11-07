package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.util.SubscriptionError
import com.gooserelay.gooserelayvpn.util.SubscriptionFetchException
import com.gooserelay.gooserelayvpn.util.SubscriptionFetcher
import org.junit.Test
import java.io.ByteArrayInputStream
import java.net.URL

class SubscriptionFetcherTest {

    private val decode: (String) -> String? = { value ->
        runCatching {
            String(java.util.Base64.getDecoder().decode(value), Charsets.UTF_8)
        }.getOrNull()
    }

    private fun shareLine(json: String): String =
        "goose-relay://" + java.util.Base64.getEncoder()
            .encodeToString(json.toByteArray(Charsets.UTF_8))

    private val profileJson =
        """{"name":"node-a","socks_host":"1.2.3.4","script_keys":["key"]}"""

    @Test
    fun `two share lines parse into two profiles`() {
        val feed = shareLine(profileJson) + "\n" + shareLine("""{"name":"node-b"}""")
        val result = SubscriptionFetcher.parseFeed(feed, decode)
        assertThat(result.profiles).hasSize(2)
        assertThat(result.profiles.map { it.name }).containsExactly("node-a", "node-b").inOrder()
        assertThat(result.skippedLines).isEqualTo(0)
    }

    @Test
    fun `leading bom on the body is stripped`() {
        val result = SubscriptionFetcher.parseFeed("\uFEFF" + shareLine(profileJson), decode)
        assertThat(result.profiles).hasSize(1)
        assertThat(result.profiles[0].name).isEqualTo("node-a")
    }

    @Test
    fun `bom after base64 decoding is stripped`() {
        val result = SubscriptionFetcher.parseFeed(shareLine("\uFEFF$profileJson"), decode)
        assertThat(result.profiles).hasSize(1)
        assertThat(result.profiles[0].name).isEqualTo("node-a")
    }

    @Test
    fun `comment and blank lines are ignored`() {
        val feed = buildString {
            appendLine("# comment")
            appendLine()
            appendLine("// another comment")
            appendLine(shareLine(profileJson))
            appendLine("   ")
        }
        val result = SubscriptionFetcher.parseFeed(feed, decode)
        assertThat(result.profiles).hasSize(1)
        assertThat(result.skippedLines).isEqualTo(0)
    }

    @Test
    fun `upper case share scheme is accepted`() {
        val base64 = java.util.Base64.getEncoder()
            .encodeToString(profileJson.toByteArray(Charsets.UTF_8))
        val result = SubscriptionFetcher.parseFeed("GOOSE-RELAY://$base64", decode)
        assertThat(result.profiles).hasSize(1)
        assertThat(result.profiles[0].name).isEqualTo("node-a")
    }

    @Test
    fun `invalid base64 line is skipped without dropping valid ones`() {
        val feed = shareLine(profileJson) + "\n" + "not-valid-base64!!!" + "\n" +
            shareLine("""{"name":"node-c"}""")
        val result = SubscriptionFetcher.parseFeed(feed, decode)
        assertThat(result.profiles).hasSize(2)
        assertThat(result.profiles.map { it.name }).containsExactly("node-a", "node-c").inOrder()
        assertThat(result.skippedLines).isEqualTo(1)
    }

    @Test
    fun `raw json body parses as one profile`() {
        val result = SubscriptionFetcher.parseFeed(profileJson, decode)
        assertThat(result.profiles).hasSize(1)
        assertThat(result.profiles[0].name).isEqualTo("node-a")
    }

    @Test
    fun `raw json line among share lines parses`() {
        val feed = shareLine(profileJson) + "\n" + """{"name":"raw-line"}"""
        val result = SubscriptionFetcher.parseFeed(feed, decode)
        assertThat(result.profiles).hasSize(2)
        assertThat(result.profiles.map { it.name }).containsExactly("node-a", "raw-line").inOrder()
    }

    @Test
    fun `raw json first line followed by share lines parses both`() {
        val feed = profileJson + "\n" + shareLine("""{"name":"node-b"}""")
        val result = SubscriptionFetcher.parseFeed(feed, decode)
        assertThat(result.profiles).hasSize(2)
        assertThat(result.profiles.map { it.name }).containsExactly("node-a", "node-b").inOrder()
        assertThat(result.skippedLines).isEqualTo(0)
    }

    @Test
    fun `malformed json body yields no profiles`() {
        val result = SubscriptionFetcher.parseFeed("""{"name":"x", """, decode)
        assertThat(result.profiles).isEmpty()
        assertThat(result.skippedLines).isEqualTo(1)
    }

    @Test
    fun `body with only comments yields no profiles`() {
        val result = SubscriptionFetcher.parseFeed("# one\n// two\n\n", decode)
        assertThat(result.profiles).isEmpty()
    }

    @Test
    fun `readCapped rejects one byte over the limit`() {
        val oversized = ByteArrayInputStream(ByteArray(SubscriptionFetcher.MAX_BYTES + 1))
        val failure = runCatching { SubscriptionFetcher.readCapped(oversized) }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SubscriptionFetchException::class.java)
        assertThat((failure as SubscriptionFetchException).error)
            .isEqualTo(SubscriptionError.TOO_LARGE)
    }

    @Test
    fun `readCapped accepts exactly the limit`() {
        val exact = ByteArrayInputStream(ByteArray(SubscriptionFetcher.MAX_BYTES))
        val bytes = SubscriptionFetcher.readCapped(exact)
        assertThat(bytes).hasLength(SubscriptionFetcher.MAX_BYTES)
    }

    @Test
    fun `requireHttpUrl rejects non-http schemes`() {
        val failure = runCatching {
            SubscriptionFetcher.requireHttpUrl("file:///x")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SubscriptionFetchException::class.java)
        assertThat((failure as SubscriptionFetchException).error)
            .isEqualTo(SubscriptionError.INSECURE_SCHEME)
    }

    @Test
    fun `requireHttpUrl rejects garbage`() {
        val failure = runCatching {
            SubscriptionFetcher.requireHttpUrl("not a url")
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SubscriptionFetchException::class.java)
        assertThat((failure as SubscriptionFetchException).error)
            .isEqualTo(SubscriptionError.INVALID_URL)
    }

    @Test
    fun `fetch rejects a file url without hitting the network`() {
        val failure = runCatching {
            SubscriptionFetcher.fetch("file:///x", decode)
        }.exceptionOrNull()
        assertThat(failure).isInstanceOf(SubscriptionFetchException::class.java)
        assertThat((failure as SubscriptionFetchException).error)
            .isEqualTo(SubscriptionError.INSECURE_SCHEME)
    }

    @Test
    fun `resolveRedirect rejects a file location`() {
        val current = URL("https://example.com/a/b")
        assertThat(SubscriptionFetcher.resolveRedirect(current, "file:///x")).isNull()
    }

    @Test
    fun `resolveRedirect resolves a relative location`() {
        val current = URL("https://example.com/a/b")
        assertThat(SubscriptionFetcher.resolveRedirect(current, "next.json").toString())
            .isEqualTo("https://example.com/a/next.json")
    }
}

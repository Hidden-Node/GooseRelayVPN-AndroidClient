package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.util.ImportIntentGate
import org.junit.Test

/**
 * Tests for [ImportIntentGate]: the JSON file-import intent must be handled
 * exactly once per VIEW/SEND intent, never again on Activity recreation
 * (rotation) or for an already-handled URI.
 */
class ImportIntentGateTest {

    private val uri = "content://com.example.provider/config.json"

    @Test
    fun `first creation with new uri handles`() {
        assertThat(ImportIntentGate.shouldHandle(true, uri, null)).isTrue()
    }

    @Test
    fun `recreation never handles even with new uri`() {
        assertThat(ImportIntentGate.shouldHandle(false, uri, null)).isFalse()
    }

    @Test
    fun `recreation never handles even with different last uri`() {
        assertThat(ImportIntentGate.shouldHandle(false, uri, "content://other/x.json")).isFalse()
    }

    @Test
    fun `same uri never handles again`() {
        assertThat(ImportIntentGate.shouldHandle(true, uri, uri)).isFalse()
    }

    @Test
    fun `null uri never handles`() {
        assertThat(ImportIntentGate.shouldHandle(true, null, null)).isFalse()
    }

    @Test
    fun `blank uri never handles`() {
        assertThat(ImportIntentGate.shouldHandle(true, "   ", null)).isFalse()
        assertThat(ImportIntentGate.shouldHandle(true, "", null)).isFalse()
    }

    @Test
    fun `json mime handles regardless of extension`() {
        assertThat(ImportIntentGate.isJsonLike("application/json", "content://x/config")).isTrue()
    }

    @Test
    fun `non-json mime with json path handles`() {
        assertThat(
            ImportIntentGate.isJsonLike("application/octet-stream", "content://x/config.JSON")
        ).isTrue()
    }

    @Test
    fun `non-json mime with non-json path rejects`() {
        assertThat(
            ImportIntentGate.isJsonLike("text/plain", "content://x/config.txt")
        ).isFalse()
    }

    @Test
    fun `null mime with json path handles`() {
        assertThat(ImportIntentGate.isJsonLike(null, "content://x/config.json")).isTrue()
    }

    @Test
    fun `null uri rejects`() {
        assertThat(ImportIntentGate.isJsonLike("application/json", null)).isFalse()
        assertThat(ImportIntentGate.isJsonLike(null, null)).isFalse()
    }

    @Test
    fun `blank uri rejects`() {
        assertThat(ImportIntentGate.isJsonLike("application/json", "  ")).isFalse()
    }
}

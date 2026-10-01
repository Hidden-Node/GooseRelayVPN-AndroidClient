package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.service.formatSharingRejectDiagnostic
import org.junit.Test

class SharingDiagnosticsTest {

    @Test
    fun `formats active oldest and suppressed`() {
        val line = formatSharingRejectDiagnostic(64, 120000, 18)
        assertThat(line).startsWith("Sharing connection limit reached; rejecting client")
        assertThat(line).contains("active=64")
        assertThat(line).contains("oldest=120000ms")
        assertThat(line).contains("(18 similar rejects suppressed)")
    }

    @Test
    fun `omits suppressed suffix when zero`() {
        val line = formatSharingRejectDiagnostic(64, 5000, 0)
        assertThat(line).contains("active=64")
        assertThat(line).doesNotContain("suppressed")
    }

    @Test
    fun `unknown age when no timestamp`() {
        val line = formatSharingRejectDiagnostic(64, -1, 0)
        assertThat(line).contains("oldest=unknown")
    }

    @Test
    fun `legacy prefix preserved`() {
        val line = formatSharingRejectDiagnostic(64, 120000, 18)
        assertThat(line).startsWith("Sharing connection limit reached; rejecting client")
    }
}

package com.gooserelay.gooserelayvpn

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.ui.profiles.ProfilesViewModel
import com.gooserelay.gooserelayvpn.ui.settings.SettingsViewModel
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/**
 * Tests for the unified ProfileJsonParser (plan 027, Step 4). All entry
 * points (file intent via MainActivity, in-app file picker + URL import via
 * ProfilesViewModel, settings-page merge via SettingsViewModel) delegate to
 * this parser, so identical JSON produces identical profiles everywhere.
 *
 * NOTE: MainActivity.parseImportedProfile is private and needs an Activity +
 * Uri, so it cannot run on plain JVM unit tests (no Robolectric). Its body
 * is a one-line delegation to ProfileJsonParser.parse, covered here by the
 * parse-with-explicit-name tests.
 */
class ProfileJsonParserTest {

    private fun vm() = ProfilesViewModel(ProfileRepository(NoopProfileDao()))
    private fun settingsVm() =
        SettingsViewModel(ProfileRepository(NoopProfileDao()), SavedStateHandle())

    private class NoopProfileDao : ProfileDao {
        override fun getAllProfiles(): Flow<List<ProfileEntity>> = emptyFlow()
        override suspend fun getProfileById(id: Long): ProfileEntity? = null
        override fun getProfileByIdFlow(id: Long): Flow<ProfileEntity?> = emptyFlow()
        override suspend fun getSelectedProfile(): ProfileEntity? = null
        override fun getSelectedProfileFlow(): Flow<ProfileEntity?> = emptyFlow()
        override suspend fun getNewestProfile(): ProfileEntity? = null
        override suspend fun getAllOnce(): List<ProfileEntity> = emptyList()
        override suspend fun insertProfile(profile: ProfileEntity): Long = 0L
        override suspend fun updateProfile(profile: ProfileEntity) {}
        override suspend fun deleteProfile(profile: ProfileEntity) {}
        override suspend fun deselectAll() {}
        override suspend fun selectProfile(id: Long) {}
        override suspend fun countProfiles(): Int = 0
        override suspend fun setSelectedProfile(id: Long) {}
    }

    private fun baseProfile() = ProfileEntity(
        name = "Base",
        debugTiming = true,
        socksHost = "9.9.9.9",
        socksPort = 9999,
        socksUser = "bu",
        socksPass = "bp",
        googleHost = "5.6.7.8",
        sniJson = """["old.com"]""",
        scriptKeysText = "OLD|o@x.com",
        tunnelKey = "OLDKEY",
        coalesceStepMs = 42,
        idleSlotsPerBucket = 1
    )

    private fun fullJson(port: Int = 2080) = """
        {"name":"Work","debug_timing":true,"socks_host":"0.0.0.0",
        "socks_port":$port,"socks_user":"u","socks_pass":"p",
        "google_host":"1.2.3.4","sni":["a.com","b.com"],
        "script_keys":[{"id":"ID1","account":"a@x.com"},"plain-key"],
        "tunnel_key":"TKEY","coalesce_step_ms":7,"idle_slots_per_bucket":3}
    """.trimIndent()

    private val defaultSni = """["www.google.com", "mail.google.com", "accounts.google.com"]"""

    // Happy path through the unified parser.

    @Test
    fun `parse happy path parses all fields`() {
        val p = ProfileJsonParser.parse(fullJson(), defaultName = "File")!!
        assertThat(p.name).isEqualTo("Work")
        assertThat(p.debugTiming).isTrue()
        assertThat(p.socksHost).isEqualTo("0.0.0.0")
        assertThat(p.socksPort).isEqualTo(2080)
        assertThat(p.socksUser).isEqualTo("u")
        assertThat(p.socksPass).isEqualTo("p")
        assertThat(p.googleHost).isEqualTo("1.2.3.4")
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
        assertThat(p.scriptKeysText).isEqualTo("ID1|a@x.com\nplain-key")
        assertThat(p.tunnelKey).isEqualTo("TKEY")
        assertThat(p.coalesceStepMs).isEqualTo(7)
        assertThat(p.idleSlotsPerBucket).isEqualTo(3)
    }

    @Test
    fun `parse defaults when only tunnel key present`() {
        val p = ProfileJsonParser.parse("""{"tunnel_key":"k"}""")!!
        assertThat(p.name).isEqualTo("Imported")
        assertThat(p.socksHost).isEqualTo("127.0.0.1")
        assertThat(p.socksPort).isEqualTo(1080)
        assertThat(p.googleHost).isEqualTo("216.239.38.120")
        assertThat(p.sniJson).isEqualTo(defaultSni)
        assertThat(p.scriptKeysText).isEqualTo("")
        assertThat(p.tunnelKey).isEqualTo("k")
        assertThat(p.coalesceStepMs).isEqualTo(0)
        assertThat(p.idleSlotsPerBucket).isEqualTo(2)
    }

    @Test
    fun `parse accepts json without script keys or tunnel key`() {
        // The old in-app copies gated on script_keys/tunnel_key; the unified
        // parser is permissive by maintainer decision (plan 027).
        val p = ProfileJsonParser.parse("""{"name":"x","google_host":"1.2.3.4"}""")!!
        assertThat(p.name).isEqualTo("x")
        assertThat(p.googleHost).isEqualTo("1.2.3.4")
        assertThat(p.scriptKeysText).isEqualTo("")
        assertThat(p.tunnelKey).isEqualTo("")
    }

    // Port regression: 1080 (and sub-1024 ports) survive every entry point.

    @Test
    fun `port 1080 survives the unified parser`() {
        assertThat(ProfileJsonParser.parse("""{"socks_port":1080}""")!!.socksPort)
            .isEqualTo(1080)
    }

    @Test
    fun `port 1080 survives the viewModel delegation`() {
        assertThat(vm().parseProfileFromJson("""{"tunnel_key":"k","socks_port":1080}""")!!.socksPort)
            .isEqualTo(1080)
    }

    @Test
    fun `port 1080 survives the settings merge`() {
        assertThat(settingsVm().importJsonToProfile(baseProfile(), """{"socks_port":1080}""")!!.socksPort)
            .isEqualTo(1080)
    }

    @Test
    fun `sub-1024 ports are no longer rewritten to 1024`() {
        // Regression for the old 1024-floor clamp in the ViewModel copy.
        assertThat(ProfileJsonParser.parse("""{"socks_port":443}""")!!.socksPort)
            .isEqualTo(443)
        assertThat(vm().parseProfileFromJson("""{"tunnel_key":"k","socks_port":443}""")!!.socksPort)
            .isEqualTo(443)
    }

    @Test
    fun `port clamps into 1_65535 range`() {
        assertThat(ProfileJsonParser.parse("""{"socks_port":99999}""")!!.socksPort)
            .isEqualTo(65535)
        assertThat(ProfileJsonParser.parse("""{"socks_port":0}""")!!.socksPort)
            .isEqualTo(1)
    }

    // sni variants.

    @Test
    fun `sni array parses`() {
        val p = ProfileJsonParser.parse("""{"sni":["a.com","b.com"]}""")!!
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
    }

    @Test
    fun `sni primitive csv splits into entries`() {
        val p = ProfileJsonParser.parse("""{"sni":"a.com, b.com"}""")!!
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
    }

    @Test
    fun `sni single primitive wraps as one entry`() {
        val p = ProfileJsonParser.parse("""{"sni":"solo.com"}""")!!
        assertThat(p.sniJson).isEqualTo("""["solo.com"]""")
    }

    @Test
    fun `sni absent falls back to defaults`() {
        val p = ProfileJsonParser.parse("""{"tunnel_key":"k"}""")!!
        assertThat(p.sniJson).isEqualTo(defaultSni)
    }

    // script_keys variants.

    @Test
    fun `script keys object array and string array`() {
        val p = ProfileJsonParser.parse(
            """{"script_keys":[{"id":"A","account":"b@x.com"},{"id":"B"},"  C  ",{"id":""}]}"""
        )!!
        assertThat(p.scriptKeysText).isEqualTo("A|b@x.com\nB\nC")
    }

    @Test
    fun `script keys primitive trims`() {
        val p = ProfileJsonParser.parse("""{"script_keys":"  plain  "}""")!!
        assertThat(p.scriptKeysText).isEqualTo("plain")
    }

    @Test
    fun `script keys absent defaults to empty`() {
        val p = ProfileJsonParser.parse("""{"tunnel_key":"k"}""")!!
        assertThat(p.scriptKeysText).isEqualTo("")
    }

    // Invalid JSON -> null for both variants.

    @Test
    fun `invalid json returns null`() {
        assertThat(ProfileJsonParser.parse("not json")).isNull()
        assertThat(ProfileJsonParser.mergeInto(baseProfile(), "not json")).isNull()
    }

    @Test
    fun `empty and null-literal json return null`() {
        // Gson returns a null root (instead of throwing) for these inputs.
        assertThat(ProfileJsonParser.parse("")).isNull()
        assertThat(ProfileJsonParser.parse("null")).isNull()
        assertThat(ProfileJsonParser.mergeInto(baseProfile(), "")).isNull()
        assertThat(vm().parseProfileFromJson("")).isNull()
    }

    // Settings merge variant.

    @Test
    fun `merge happy path replaces present fields keeps name`() {
        val p = settingsVm().importJsonToProfile(baseProfile(), fullJson())!!
        assertThat(p.name).isEqualTo("Base")
        assertThat(p.debugTiming).isTrue()
        assertThat(p.socksHost).isEqualTo("0.0.0.0")
        assertThat(p.socksPort).isEqualTo(2080)
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
        assertThat(p.scriptKeysText).isEqualTo("ID1|a@x.com\nplain-key")
        assertThat(p.tunnelKey).isEqualTo("TKEY")
        assertThat(p.coalesceStepMs).isEqualTo(7)
        assertThat(p.idleSlotsPerBucket).isEqualTo(3)
    }

    @Test
    fun `merge absent fields keep existing values`() {
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"tunnel_key":"NEW"}""")!!
        assertThat(p.socksHost).isEqualTo("9.9.9.9")
        assertThat(p.socksPort).isEqualTo(9999)
        assertThat(p.socksUser).isEqualTo("bu")
        assertThat(p.googleHost).isEqualTo("5.6.7.8")
        assertThat(p.tunnelKey).isEqualTo("NEW")
        assertThat(p.scriptKeysText).isEqualTo("OLD|o@x.com")
        assertThat(p.coalesceStepMs).isEqualTo(42)
        assertThat(p.idleSlotsPerBucket).isEqualTo(1)
    }

    @Test
    fun `merge sni primitive csv replaces`() {
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"sni":"a.com, b.com"}""")!!
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
    }

    @Test
    fun `merge script keys primitive replaces`() {
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"script_keys":"plain"}""")!!
        assertThat(p.scriptKeysText).isEqualTo("plain")
    }

    // Name / remoteUrl propagation.

    @Test
    fun `parse stores remoteUrl when provided`() {
        val p = ProfileJsonParser.parse(
            """{"tunnel_key":"k"}""", remoteUrl = "https://example.com/p.json"
        )!!
        assertThat(p.remoteUrl).isEqualTo("https://example.com/p.json")
    }

    @Test
    fun `parse defaults name to defaultName when missing`() {
        val p = ProfileJsonParser.parse("""{"tunnel_key":"k"}""", defaultName = "fallback")!!
        assertThat(p.name).isEqualTo("fallback")
    }

    @Test
    fun `viewModel delegation propagates remoteUrl and defaultName`() {
        val p = vm().parseProfileFromJson(
            """{"tunnel_key":"k"}""",
            defaultName = "fallback",
            remoteUrl = "https://example.com/p.json"
        )!!
        assertThat(p.name).isEqualTo("fallback")
        assertThat(p.remoteUrl).isEqualTo("https://example.com/p.json")
    }
}

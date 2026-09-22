package com.gooserelay.gooserelayvpn

import androidx.lifecycle.SavedStateHandle
import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.ui.profiles.ProfilesViewModel
import com.gooserelay.gooserelayvpn.ui.profiles.parseProfileFromJson as parseScreenProfile
import com.gooserelay.gooserelayvpn.ui.settings.SettingsViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

/**
 * Characterization tests for the four profile-JSON import parsers (plan 027,
 * Step 1). Each assertion pins the behavior of the CURRENT copies as they
 * behave TODAY — including drift between copies. Step 4 re-points these at
 * the unified ProfileJsonParser and removes the bug-pinning tests.
 *
 * NOTE: MainActivity.parseImportedProfile is private and needs an Activity +
 * Uri, so it cannot run on plain JVM unit tests (no Robolectric). Its
 * behavior was verified by inspection: socks_port coerceIn(1, 65535)
 * (1080 stays 1080), sni primitive CSV-split, no required-field gate.
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

    // 1. Full happy path, per copy (port 2080: unaffected by any clamp).

    @Test
    fun `viewModel happy path parses all fields`() {
        val p = vm().parseProfileFromJson(fullJson(), defaultName = "File")!!
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
    fun `screen top-level happy path parses all fields`() {
        val p = parseScreenProfile(fullJson(), "File")!!
        assertThat(p.name).isEqualTo("Work")
        assertThat(p.socksPort).isEqualTo(2080)
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
        assertThat(p.scriptKeysText).isEqualTo("ID1|a@x.com\nplain-key")
        assertThat(p.tunnelKey).isEqualTo("TKEY")
        assertThat(p.remoteUrl).isNull()
    }

    @Test
    fun `settings merge happy path replaces present fields keeps name`() {
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

    // 2. socks_port 1080 drift.

    @Test
    fun `urlImportPinsKnownPortCoercionBug`() {
        // Live bug: the ViewModel copy clamps into 1024..65535, rewriting
        // sub-1024 ports to 1024. (Plan text illustrated this with 1080, but
        // 1080 lies inside 1024..65535 and passes through; the drifted floor
        // is the actual bug — e.g. 443 becomes 1024.)
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k","socks_port":443}""")!!
        assertThat(p.socksPort).isEqualTo(1024)
    }

    @Test
    fun `screen top-level keeps port 1080 raw`() {
        val p = parseScreenProfile("""{"tunnel_key":"k","socks_port":1080}""")!!
        assertThat(p.socksPort).isEqualTo(1080)
    }

    @Test
    fun `settings merge keeps port 1080`() {
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"socks_port":1080}""")!!
        assertThat(p.socksPort).isEqualTo(1080)
    }

    // 3. Defaults when only tunnel_key is present.

    @Test
    fun `viewModel defaults when only tunnel key present`() {
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k"}""")!!
        assertThat(p.name).isEqualTo("Imported")
        assertThat(p.socksHost).isEqualTo("127.0.0.1")
        assertThat(p.socksPort).isEqualTo(1080)
        assertThat(p.googleHost).isEqualTo("216.239.38.120")
        assertThat(p.sniJson).isEqualTo(
            """["www.google.com", "mail.google.com", "accounts.google.com"]"""
        )
        assertThat(p.scriptKeysText).isEqualTo("")
        assertThat(p.tunnelKey).isEqualTo("k")
        assertThat(p.coalesceStepMs).isEqualTo(0)
        assertThat(p.idleSlotsPerBucket).isEqualTo(2)
    }

    @Test
    fun `screen top-level defaults when only tunnel key present`() {
        val p = parseScreenProfile("""{"tunnel_key":"k"}""")!!
        assertThat(p.name).isEqualTo("Imported")
        assertThat(p.socksPort).isEqualTo(1080)
        assertThat(p.sniJson).isEqualTo(
            """["www.google.com", "mail.google.com", "accounts.google.com"]"""
        )
        assertThat(p.scriptKeysText).isEqualTo("")
    }

    // 4. sni variants per copy.

    @Test
    fun `viewModel sni array parses`() {
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k","sni":["a.com","b.com"]}""")!!
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
    }

    @Test
    fun `viewModel sni primitive wraps whole string as single entry`() {
        // Drift: CSV "a.com, b.com" becomes ONE entry, unlike MainActivity's split.
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k","sni":"a.com, b.com"}""")!!
        assertThat(p.sniJson).isEqualTo("""["a.com, b.com"]""")
    }

    @Test
    fun `viewModel sni absent falls back to defaults`() {
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k"}""")!!
        assertThat(p.sniJson).isEqualTo(
            """["www.google.com", "mail.google.com", "accounts.google.com"]"""
        )
    }

    @Test
    fun `settings sni primitive falls back to defaults today`() {
        // Drift: settings copy only honors arrays; primitives reset to defaults.
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"sni":"solo.com"}""")!!
        assertThat(p.sniJson).isEqualTo(
            """["www.google.com","mail.google.com","accounts.google.com"]"""
        )
    }

    @Test
    fun `settings sni array replaces`() {
        val p = settingsVm().importJsonToProfile(
            baseProfile(), """{"sni":["a.com", " b.com "]}"""
        )!!
        assertThat(p.sniJson).isEqualTo("""["a.com","b.com"]""")
    }

    // 5. script_keys variants per copy.

    @Test
    fun `viewModel script keys object array and string array`() {
        val obj = vm().parseProfileFromJson(
            """{"tunnel_key":"k","script_keys":[{"id":"A","account":"b@x.com"},{"id":"B"},"  C  ",{"id":""}]}"""
        )!!
        assertThat(obj.scriptKeysText).isEqualTo("A|b@x.com\nB\nC")
    }

    @Test
    fun `viewModel script keys primitive trims`() {
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k","script_keys":"  plain  "}""")!!
        assertThat(p.scriptKeysText).isEqualTo("plain")
    }

    @Test
    fun `settings script keys primitive keeps existing today`() {
        // Drift: settings copy only honors arrays; primitives keep the old value.
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"script_keys":"plain"}""")!!
        assertThat(p.scriptKeysText).isEqualTo("OLD|o@x.com")
    }

    @Test
    fun `settings script keys absent keeps existing`() {
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"tunnel_key":"NEW"}""")!!
        assertThat(p.scriptKeysText).isEqualTo("OLD|o@x.com")
    }

    // 6. Invalid JSON -> null for every copy.

    @Test
    fun `invalid json returns null in all copies`() {
        assertThat(vm().parseProfileFromJson("not json")).isNull()
        assertThat(parseScreenProfile("not json")).isNull()
        assertThat(settingsVm().importJsonToProfile(baseProfile(), "not json")).isNull()
    }

    // 7. Settings merge fallbacks.

    @Test
    fun `settings merge absent fields keep existing except sni coalesce idle`() {
        // Current settings behavior: absent scalar fields keep existing values,
        // but sni resets to defaults, coalesce_step_ms resets to 0 and
        // idle_slots_per_bucket resets to 2.
        val p = settingsVm().importJsonToProfile(baseProfile(), """{"tunnel_key":"NEW"}""")!!
        assertThat(p.socksHost).isEqualTo("9.9.9.9")
        assertThat(p.socksPort).isEqualTo(9999)
        assertThat(p.socksUser).isEqualTo("bu")
        assertThat(p.googleHost).isEqualTo("5.6.7.8")
        assertThat(p.tunnelKey).isEqualTo("NEW")
        assertThat(p.sniJson).isEqualTo(
            """["www.google.com","mail.google.com","accounts.google.com"]"""
        )
        assertThat(p.coalesceStepMs).isEqualTo(0)
        assertThat(p.idleSlotsPerBucket).isEqualTo(2)
    }

    // 8. remoteUrl propagation (ViewModel copy only).

    @Test
    fun `viewModel stores remoteUrl when provided`() {
        val p = vm().parseProfileFromJson(
            """{"tunnel_key":"k"}""", remoteUrl = "https://example.com/p.json"
        )!!
        assertThat(p.remoteUrl).isEqualTo("https://example.com/p.json")
    }

    @Test
    fun `viewModel defaults name to defaultName when missing`() {
        val p = vm().parseProfileFromJson("""{"tunnel_key":"k"}""", defaultName = "fallback")!!
        assertThat(p.name).isEqualTo("fallback")
    }
}

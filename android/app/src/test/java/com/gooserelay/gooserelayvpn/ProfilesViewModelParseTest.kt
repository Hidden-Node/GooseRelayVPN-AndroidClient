package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.local.SubscriptionDao
import com.gooserelay.gooserelayvpn.data.local.SubscriptionEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.data.repository.SubscriptionRepository
import com.gooserelay.gooserelayvpn.ui.profiles.ProfilesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class ProfilesViewModelParseTest {

    private fun vm(): ProfilesViewModel {
        val dao = NoopProfileDao()
        return ProfilesViewModel(
            ProfileRepository(dao),
            SubscriptionRepository(NoopSubscriptionDao(), dao)
        )
    }

    private class NoopSubscriptionDao : SubscriptionDao {
        override fun getAllSubscriptions(): Flow<List<SubscriptionEntity>> = emptyFlow()
        override suspend fun getSubscriptionById(id: Long): SubscriptionEntity? = null
        override suspend fun getSubscriptionByUrl(url: String): SubscriptionEntity? = null
        override suspend fun insertSubscription(sub: SubscriptionEntity): Long = 0L
        override suspend fun updateSubscription(sub: SubscriptionEntity) {}
    }

    private class NoopProfileDao : ProfileDao {
        override fun getAllProfiles(): Flow<List<ProfileEntity>> = emptyFlow()
        override suspend fun getProfileById(id: Long): ProfileEntity? = null
        override fun getProfileByIdFlow(id: Long): Flow<ProfileEntity?> = emptyFlow()
        override suspend fun getSelectedProfile(): ProfileEntity? = null
        override fun getSelectedProfileFlow(): Flow<ProfileEntity?> = emptyFlow()
        override suspend fun getNewestProfile(): ProfileEntity? = null
        override suspend fun insertProfile(profile: ProfileEntity): Long = 0L
        override suspend fun updateProfile(profile: ProfileEntity) {}
        override suspend fun deleteProfile(profile: ProfileEntity) {}
        override suspend fun deselectAll() {}
        override suspend fun selectProfile(id: Long) {}
        override suspend fun countProfiles(): Int = 0
        override suspend fun countSubscriptions(subId: Long): Int = 0
        override suspend fun deleteProfilesBySubscription(subId: Long) {}
        override suspend fun deleteSubscriptionRow(subId: Long) {}
        override suspend fun setSelectedProfile(id: Long) {}
    }

    @Test
    fun `returns null for empty json`() {
        assertThat(vm().parseProfileFromJson("")).isNull()
    }

    @Test
    fun `accepts json without script_keys and tunnel_key`() {
        // Unified parser (plan 027) has no required-field gate.
        val json = """{"name":"x","google_host":"1.2.3.4"}"""
        assertThat(vm().parseProfileFromJson(json)).isNotNull()
    }

    @Test
    fun `clamps idleSlotsPerBucket to 1_3 range`() {
        val json = """{"tunnel_key":"k","idle_slots_per_bucket":99}"""
        val p = vm().parseProfileFromJson(json)
        assertThat(p).isNotNull()
        assertThat(p!!.idleSlotsPerBucket).isEqualTo(3)
    }

    @Test
    fun `clamps socksPort to 1_65535 range`() {
        val tooHigh = """{"tunnel_key":"k","socks_port":99999}"""
        assertThat(vm().parseProfileFromJson(tooHigh)!!.socksPort).isEqualTo(65535)

        val tooLow = """{"tunnel_key":"k","socks_port":0}"""
        assertThat(vm().parseProfileFromJson(tooLow)!!.socksPort).isEqualTo(1)

        val unprivileged = """{"tunnel_key":"k","socks_port":80}"""
        assertThat(vm().parseProfileFromJson(unprivileged)!!.socksPort).isEqualTo(80)
    }

    @Test
    fun `accepts script_keys as object array and as primitive string`() {
        val objArr = """{"tunnel_key":"k","script_keys":[{"id":"A","account":"b@x.com"},{"id":"B"}]}"""
        val obj = vm().parseProfileFromJson(objArr)!!
        assertThat(obj.scriptKeysText).isEqualTo("A|b@x.com\nB")

        val prim = """{"tunnel_key":"k","script_keys":"plain"}"""
        val primP = vm().parseProfileFromJson(prim)!!
        assertThat(primP.scriptKeysText).isEqualTo("plain")
    }

    @Test
    fun `defaults name to defaultName when missing`() {
        val json = """{"tunnel_key":"k"}"""
        val p = vm().parseProfileFromJson(json, defaultName = "fallback")!!
        assertThat(p.name).isEqualTo("fallback")
    }

    @Test
    fun `openAddSubscriptionDialog with initialUrl pre-fills state url`() {
        val viewModel = vm()
        viewModel.openAddSubscriptionDialog(initialUrl = "https://example.com/feed")
        val state = viewModel.addSubscription.value
        assertThat(state.visible).isTrue()
        assertThat(state.url).isEqualTo("https://example.com/feed")
        assertThat(state.name).isEmpty()
        assertThat(state.errorRes).isNull()
    }

    @Test
    fun `extractWebUrl with valid http and https urls`() {
        assertThat(ProfilesViewModel.extractWebUrl("http://example.com/sub")).isEqualTo("http://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("https://example.com/sub")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("HTTPS://EXAMPLE.COM/SUB")).isEqualTo("HTTPS://EXAMPLE.COM/SUB")
    }

    @Test
    fun `extractWebUrl trims whitespace tabs and extracts first token of multiline text`() {
        assertThat(ProfilesViewModel.extractWebUrl("   https://example.com/sub   ")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("https://example.com/sub\t#US-Node\nUpdated 2026")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("https://example.com/sub (US Server)\nUpdated 2026")).isEqualTo("https://example.com/sub")
    }

    @Test
    fun `extractWebUrl strips enclosing brackets and trailing punctuation`() {
        assertThat(ProfilesViewModel.extractWebUrl("<https://example.com/sub>")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("\"https://example.com/sub\"")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("[https://example.com/sub]")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("https://example.com/sub.")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("https://example.com/sub,")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("(https://example.com/sub)")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("(https://example.com/sub).")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("https://example.com/feed_(v2)")).isEqualTo("https://example.com/feed_(v2)")
        assertThat(ProfilesViewModel.extractWebUrl("(https://example.com/feed_(v2))")).isEqualTo("https://example.com/feed_(v2)")
    }

    @Test
    fun `extractWebUrl finds url when preceded by labels or leading newlines`() {
        assertThat(ProfilesViewModel.extractWebUrl("\n\nhttps://example.com/sub")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("Config: https://example.com/sub")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("My Subscription:\nhttps://example.com/sub\nUpdated 2026")).isEqualTo("https://example.com/sub")
    }

    @Test
    fun `extractWebUrl extracts url from markdown link syntax`() {
        assertThat(ProfilesViewModel.extractWebUrl("[Link](https://example.com/sub)")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("[My Subscription](https://example.com/sub)")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("Check [here](https://example.com/sub).")).isEqualTo("https://example.com/sub")
        assertThat(ProfilesViewModel.extractWebUrl("[Node](https://example.com/feed_(v2))")).isEqualTo("https://example.com/feed_(v2)")
    }

    @Test
    fun `extractWebUrl returns null for non-http text or json`() {
        assertThat(ProfilesViewModel.extractWebUrl(null)).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("   ")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("https://")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("http://")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("goose-relay://host:1234")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("""{"name":"test"}""")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("""{"remoteUrl":"https://example.com"}""")).isNull()
        assertThat(ProfilesViewModel.extractWebUrl("ftp://example.com/sub")).isNull()
    }
}

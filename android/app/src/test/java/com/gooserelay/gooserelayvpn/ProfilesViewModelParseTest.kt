package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.ui.profiles.ProfilesViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Test

class ProfilesViewModelParseTest {

    private fun vm() = ProfilesViewModel(ProfileRepository(NoopProfileDao()))

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
        override suspend fun setSelectedProfile(id: Long) {}
    }

    @Test
    fun `returns null for empty json`() {
        assertThat(vm().parseProfileFromJson("")).isNull()
    }

    @Test
    fun `returns null when json lacks script_keys and tunnel_key`() {
        val json = """{"name":"x","google_host":"1.2.3.4"}"""
        assertThat(vm().parseProfileFromJson(json)).isNull()
    }

    @Test
    fun `clamps idleSlotsPerBucket to 1_3 range`() {
        val json = """{"tunnel_key":"k","idle_slots_per_bucket":99}"""
        val p = vm().parseProfileFromJson(json)
        assertThat(p).isNotNull()
        assertThat(p!!.idleSlotsPerBucket).isEqualTo(3)
    }

    @Test
    fun `clamps socksPort to 1024_65535 range`() {
        val tooHigh = """{"tunnel_key":"k","socks_port":99999}"""
        assertThat(vm().parseProfileFromJson(tooHigh)!!.socksPort).isEqualTo(65535)

        val tooLow = """{"tunnel_key":"k","socks_port":0}"""
        assertThat(vm().parseProfileFromJson(tooLow)!!.socksPort).isEqualTo(1024)

        val privileged = """{"tunnel_key":"k","socks_port":80}"""
        assertThat(vm().parseProfileFromJson(privileged)!!.socksPort).isEqualTo(1024)
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
    fun `stores remoteUrl when provided`() {
        val json = """{"tunnel_key":"k"}"""
        val p = vm().parseProfileFromJson(json, remoteUrl = "https://example.com/p.json")!!
        assertThat(p.remoteUrl).isEqualTo("https://example.com/p.json")
    }

    @Test
    fun `defaults name to defaultName when missing`() {
        val json = """{"tunnel_key":"k"}"""
        val p = vm().parseProfileFromJson(json, defaultName = "fallback")!!
        assertThat(p.name).isEqualTo("fallback")
    }
}

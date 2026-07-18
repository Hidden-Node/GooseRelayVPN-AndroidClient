package com.gooserelay.gooserelayvpn

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import org.junit.Test

class ConfigGeneratorTest {
    private val gson = Gson()

    private fun parse(json: String): JsonObject =
        gson.fromJson(json, JsonObject::class.java)

    @Test
    fun `default profile emits required fields`() {
        val profile = ProfileEntity(name = "test")
        val root = parse(ConfigGenerator.generateConfig(profile))
        assertThat(root.has("socks_host")).isTrue()
        assertThat(root.has("google_host")).isTrue()
        assertThat(root.has("tunnel_key")).isTrue()
        assertThat(root.has("sni")).isTrue()
        assertThat(root.has("script_keys")).isTrue()
        assertThat(root.has("idle_slots_per_bucket")).isTrue()
        assertThat(root.getAsJsonArray("sni").size()).isGreaterThan(0)
        assertThat(root.getAsJsonArray("script_keys").size()).isEqualTo(0)
    }

    @Test
    fun `socks_port is omitted when 1080 and present otherwise`() {
        val default = parse(ConfigGenerator.generateConfig(ProfileEntity(name = "x")))
        assertThat(default.has("socks_port")).isFalse()

        val custom = parse(ConfigGenerator.generateConfig(ProfileEntity(name = "x", socksPort = 1234)))
        assertThat(custom.has("socks_port")).isTrue()
        assertThat(custom.get("socks_port").asInt).isEqualTo(1234)
    }

    @Test
    fun `socks_user and socks_pass are omitted when blank`() {
        val blank = parse(ConfigGenerator.generateConfig(ProfileEntity(name = "x")))
        assertThat(blank.has("socks_user")).isFalse()
        assertThat(blank.has("socks_pass")).isFalse()

        val filled = parse(ConfigGenerator.generateConfig(
            ProfileEntity(name = "x", socksUser = "alice", socksPass = "secret")
        ))
        assertThat(filled.get("socks_user").asString).isEqualTo("alice")
        assertThat(filled.get("socks_pass").asString).isEqualTo("secret")
    }

    @Test
    fun `script_keys parses id_pipe_account lines`() {
        val p = ProfileEntity(name = "x", scriptKeysText = "DEPLOY123\nACC456|user@example.com")
        val root = parse(ConfigGenerator.generateConfig(p))
        val arr = root.getAsJsonArray("script_keys")
        assertThat(arr.size()).isEqualTo(2)
        assertThat(arr[0].asJsonObject.get("id").asString).isEqualTo("DEPLOY123")
        assertThat(arr[1].asJsonObject.get("id").asString).isEqualTo("ACC456")
        assertThat(arr[1].asJsonObject.get("account").asString).isEqualTo("user@example.com")
    }

    @Test
    fun `script_keys empty input produces empty array`() {
        val p = ProfileEntity(name = "x", scriptKeysText = "")
        val root = parse(ConfigGenerator.generateConfig(p))
        assertThat(root.getAsJsonArray("script_keys").size()).isEqualTo(0)
    }

    @Test
    fun `sni handles blank and valid json array`() {
        val blank = ProfileEntity(name = "x", sniJson = "")
        val rootBlank = parse(ConfigGenerator.generateConfig(blank))
        assertThat(rootBlank.getAsJsonArray("sni").size()).isEqualTo(0)

        val valid = ProfileEntity(name = "x", sniJson = "[\"example.com\"]")
        val rootValid = parse(ConfigGenerator.generateConfig(valid))
        assertThat(rootValid.getAsJsonArray("sni").size()).isEqualTo(1)
        assertThat(rootValid.getAsJsonArray("sni")[0].asString).isEqualTo("example.com")
    }

    @Test
    fun `idle_slots_per_bucket is always present`() {
        val p = ProfileEntity(name = "x", idleSlotsPerBucket = 1)
        val root = parse(ConfigGenerator.generateConfig(p))
        assertThat(root.has("idle_slots_per_bucket")).isTrue()
        assertThat(root.get("idle_slots_per_bucket").asInt).isEqualTo(1)
    }

    @Test
    fun `coalesce_step_ms is omitted when 0`() {
        val zero = parse(ConfigGenerator.generateConfig(ProfileEntity(name = "x")))
        assertThat(zero.has("coalesce_step_ms")).isFalse()

        val nonzero = parse(ConfigGenerator.generateConfig(ProfileEntity(name = "x", coalesceStepMs = 500)))
        assertThat(nonzero.get("coalesce_step_ms").asInt).isEqualTo(500)
    }
}

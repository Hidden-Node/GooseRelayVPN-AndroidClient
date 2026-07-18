package com.gooserelay.gooserelayvpn.util

import android.util.Log
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken

object ConfigGenerator {
    private val gson = Gson()

    fun generateConfig(profile: ProfileEntity): String {
        val root = JsonObject().apply {
            addProperty("debug_timing", profile.debugTiming)
            addProperty("socks_host", profile.socksHost)
            if (profile.socksPort != 1080) {
                addProperty("socks_port", profile.socksPort)
            }
            if (profile.socksUser.isNotBlank()) {
                addProperty("socks_user", profile.socksUser)
            }
            if (profile.socksPass.isNotBlank()) {
                addProperty("socks_pass", profile.socksPass)
            }
            addProperty("google_host", profile.googleHost)
            add("sni", parseSni(profile.sniJson))
            add("script_keys", parseScriptKeys(profile.scriptKeysText))
            addProperty("tunnel_key", profile.tunnelKey)
            if (profile.coalesceStepMs > 0) {
                addProperty("coalesce_step_ms", profile.coalesceStepMs)
            }
            // Always include idle_slots_per_bucket (don't skip if it's 1)
            addProperty("idle_slots_per_bucket", profile.idleSlotsPerBucket)
        }
        val json = gson.toJson(root)
        Log.d("ConfigGenerator", "Generated full config: $json")
        return json
    }

    fun exportProfileJson(profile: ProfileEntity): String = generateConfig(profile)

    private fun parseSni(value: String): JsonArray {
        if (value.isBlank()) return JsonArray()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val list = gson.fromJson<List<String>>(value, type).orEmpty().map { it.trim() }.filter { it.isNotEmpty() }
            JsonArray().apply { list.forEach { add(it) } }
        } catch (_: Exception) {
            JsonArray()
        }
    }

    private fun parseScriptKeys(text: String): JsonArray {
        val keys = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val result = JsonArray()
        Log.d("ConfigGenerator", "Parsing script keys: $keys")
        for (key in keys) {
            val trimmed = key.trim()
            if (trimmed.isEmpty()) continue
            val obj = JsonObject()
            if (trimmed.contains("|")) {
                val parts = trimmed.split("|")
                if (parts.size >= 2) {
                    obj.addProperty("id", parts[0].trim())
                    obj.addProperty("account", parts[1].trim())
                    Log.d("ConfigGenerator", "Added script key with account: id=${parts[0].trim()}, account=${parts[1].trim()}")
                } else {
                    obj.addProperty("id", trimmed)
                }
            } else {
                obj.addProperty("id", trimmed)
            }
            result.add(obj)
        }
        Log.d("ConfigGenerator", "Final script keys JSON: ${gson.toJson(result)}")
        return result
    }
}
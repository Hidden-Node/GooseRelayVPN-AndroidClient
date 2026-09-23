package com.gooserelay.gooserelayvpn.util

import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.google.gson.Gson
import com.google.gson.JsonObject

/**
 * Single parser for profile-JSON imports. All entry points (file intent,
 * in-app file picker, URL import/refresh, settings-page merge) must go
 * through here so identical JSON produces identical profiles everywhere.
 *
 * Unification decisions (approved by maintainer, plan 027):
 * - socks_port coerced into 1..65535 (fixes the old 1024-floor clamp that
 *   rewrote sub-1024 ports to 1024 on URL imports).
 * - sni primitive parsed as a comma-separated list.
 * - no required-field gate; callers may pre-validate for UX.
 */
object ProfileJsonParser {

    private val gson = Gson()
    private const val DEFAULT_SNI_JSON =
        "[\"www.google.com\", \"mail.google.com\", \"accounts.google.com\"]"

    /** Full-parse variant: absent fields become defaults. */
    fun parse(raw: String, defaultName: String? = null, remoteUrl: String? = null): ProfileEntity? {
        // Note: Gson.fromJson returns null (instead of throwing) for empty
        // input and the "null" literal, so the null root is checked
        // explicitly — a runCatching{}.map{} chain lets that NPE escape.
        return try {
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            fromRoot(root, defaultName ?: "Imported", remoteUrl)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Merge variant used by the settings page: absent scalar fields and
     * absent `script_keys` keep the existing profile's values. Absent
     * `sni` resets to the default list (matching the old settings-page
     * behavior, which always rebuilt from defaults — intentional, not a
     * bug). Present `sni` (array or CSV string) and present `script_keys`
     * (array or trimmed primitive string) replace, never merge.
     */
    fun mergeInto(profile: ProfileEntity, raw: String): ProfileEntity? {
        // See parse(): the null root is checked explicitly because Gson
        // returns null (instead of throwing) for empty/"null" input.
        return try {
            val root = gson.fromJson(raw, JsonObject::class.java) ?: return null
            profile.copy(
                debugTiming = root.get("debug_timing")?.asBoolean ?: profile.debugTiming,
                socksHost = root.get("socks_host")?.asString ?: profile.socksHost,
                socksPort = root.get("socks_port")?.asInt?.coerceIn(1, 65535) ?: profile.socksPort,
                socksUser = root.get("socks_user")?.asString ?: profile.socksUser,
                socksPass = root.get("socks_pass")?.asString ?: profile.socksPass,
                googleHost = root.get("google_host")?.asString ?: profile.googleHost,
                sniJson = parseSni(root.get("sni")),
                scriptKeysText = parseScriptKeys(root.get("script_keys")) ?: profile.scriptKeysText,
                tunnelKey = root.get("tunnel_key")?.asString ?: profile.tunnelKey,
                coalesceStepMs = root.get("coalesce_step_ms")?.asInt ?: profile.coalesceStepMs,
                idleSlotsPerBucket = root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: profile.idleSlotsPerBucket
            )
        } catch (_: Exception) {
            null
        }
    }

    private fun fromRoot(root: JsonObject, name: String, remoteUrl: String?): ProfileEntity {
        return ProfileEntity(
            name = root.get("name")?.asString ?: name,
            debugTiming = root.get("debug_timing")?.asBoolean ?: false,
            socksHost = root.get("socks_host")?.asString ?: "127.0.0.1",
            socksPort = root.get("socks_port")?.asInt?.coerceIn(1, 65535) ?: 1080,
            socksUser = root.get("socks_user")?.asString ?: "",
            socksPass = root.get("socks_pass")?.asString ?: "",
            googleHost = root.get("google_host")?.asString ?: "216.239.38.120",
            sniJson = parseSni(root.get("sni")),
            scriptKeysText = parseScriptKeys(root.get("script_keys")) ?: "",
            tunnelKey = root.get("tunnel_key")?.asString ?: "",
            coalesceStepMs = root.get("coalesce_step_ms")?.asInt ?: 0,
            idleSlotsPerBucket = root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: 2,
            remoteUrl = remoteUrl
        )
    }

    private fun parseSni(element: com.google.gson.JsonElement?): String {
        if (element == null || element.isJsonNull) return DEFAULT_SNI_JSON
        return runCatching {
            when {
                element.isJsonArray -> {
                    val list = element.asJsonArray.mapNotNull { it.asString?.trim() }.filter { it.isNotEmpty() }
                    if (list.isEmpty()) DEFAULT_SNI_JSON else gson.toJson(list)
                }
                element.isJsonPrimitive -> {
                    val list = element.asString.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    if (list.isEmpty()) DEFAULT_SNI_JSON else gson.toJson(list)
                }
                else -> DEFAULT_SNI_JSON
            }
        }.getOrDefault(DEFAULT_SNI_JSON)
    }

    private fun parseScriptKeys(element: com.google.gson.JsonElement?): String? {
        if (element == null || element.isJsonNull) return null
        return runCatching {
            when {
                element.isJsonArray -> element.asJsonArray.mapNotNull { entry ->
                    when {
                        entry.isJsonObject -> {
                            val obj = entry.asJsonObject
                            val id = obj.get("id")?.asString?.trim()
                            val account = obj.get("account")?.asString?.trim()
                            when {
                                id.isNullOrBlank() -> null
                                account.isNullOrBlank() -> id
                                else -> "$id|$account"
                            }
                        }
                        entry.isJsonPrimitive -> entry.asString.trim()
                        else -> null
                    }
                }.filter { it.isNotBlank() }.joinToString("\n")
                element.isJsonPrimitive -> element.asString.trim()
                else -> null
            }
        }.getOrNull()
    }
}

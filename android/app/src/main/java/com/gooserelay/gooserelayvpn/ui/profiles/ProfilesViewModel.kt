package com.gooserelay.gooserelayvpn.ui.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>> =
        profileRepository.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private val _updateMessage = MutableStateFlow<String?>(null)
    val updateMessage: StateFlow<String?> = _updateMessage.asStateFlow()

    fun addProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            val id = profileRepository.insertProfile(profile)
            // Auto-select the first profile
            if (profiles.value.isEmpty()) {
                profileRepository.setSelectedProfile(id)
            }
        }
    }

    fun updateProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            profileRepository.updateProfile(profile)
        }
    }

    fun deleteProfile(profile: ProfileEntity) {
        viewModelScope.launch {
            profileRepository.deleteProfile(profile)
        }
    }

    fun selectProfile(id: Long) {
        viewModelScope.launch {
            profileRepository.setSelectedProfile(id)
        }
    }

    fun updateRemoteProfile(context: Context) {
        viewModelScope.launch {
            val selectedProfile = profileRepository.getSelectedProfileFlow().first()
            if (selectedProfile == null) {
                _updateMessage.value = context.getString(R.string.profiles_no_selection_update)
                return@launch
            }

            val targetUrl = selectedProfile.remoteUrl
            if (targetUrl.isNullOrBlank()) {
                _updateMessage.value = context.getString(R.string.profiles_no_remote_url)
                return@launch
            }

            _isUpdating.value = true
            try {
                val json = withContext(Dispatchers.IO) {
                    val url = URL(targetUrl)
                    val connection = url.openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.inputStream.bufferedReader().use { it.readText() }
                }

                val remoteProfile = parseProfileFromJson(json, remoteUrl = selectedProfile.remoteUrl)
                if (remoteProfile != null) {
                    val updated = selectedProfile.copy(
                        name = if (selectedProfile.remoteUrl != null) remoteProfile.name else selectedProfile.name,
                        debugTiming = remoteProfile.debugTiming,
                        socksHost = remoteProfile.socksHost,
                        socksPort = remoteProfile.socksPort,
                        socksUser = remoteProfile.socksUser,
                        socksPass = remoteProfile.socksPass,
                        googleHost = remoteProfile.googleHost,
                        sniJson = remoteProfile.sniJson,
                        scriptKeysText = remoteProfile.scriptKeysText,
                        tunnelKey = remoteProfile.tunnelKey,
                        coalesceStepMs = remoteProfile.coalesceStepMs,
                        idleSlotsPerBucket = remoteProfile.idleSlotsPerBucket
                    )
                    profileRepository.updateProfile(updated)
                    _updateMessage.value = context.getString(R.string.profiles_update_success)
                } else {
                    _updateMessage.value = context.getString(R.string.profiles_update_error, "Invalid JSON format")
                }
            } catch (e: Exception) {
                _updateMessage.value = context.getString(R.string.profiles_update_error, e.localizedMessage ?: "Unknown error")
            } finally {
                _isUpdating.value = false
            }
        }
    }

    fun importProfileFromUrl(url: String, context: Context) {
        viewModelScope.launch {
            _isUpdating.value = true
            try {
                val json = withContext(Dispatchers.IO) {
                    val connection = URL(url).openConnection() as HttpURLConnection
                    connection.requestMethod = "GET"
                    connection.connectTimeout = 10000
                    connection.readTimeout = 10000
                    connection.inputStream.bufferedReader().use { it.readText() }
                }

                val profile = parseProfileFromJson(json, remoteUrl = url)
                if (profile != null) {
                    addProfile(profile)
                    _updateMessage.value = context.getString(R.string.profiles_update_success)
                } else {
                    _updateMessage.value = "Import failed: Invalid JSON format"
                }
            } catch (e: Exception) {
                _updateMessage.value = "Import failed: ${e.localizedMessage}"
            } finally {
                _isUpdating.value = false
            }
        }
    }

    fun clearUpdateMessage() {
        _updateMessage.value = null
    }

    fun parseProfileFromJson(raw: String, defaultName: String? = null, remoteUrl: String? = null): ProfileEntity? {
        return try {
            val root = Gson().fromJson(raw, JsonObject::class.java)

            // Check if it has at least one identifying part
            if (!root.has("script_keys") && !root.has("tunnel_key")) return null

            val name = root.get("name")?.asString ?: defaultName ?: "Imported"
            val debugTiming = root.get("debug_timing")?.asBoolean ?: false
            val socksHost = root.get("socks_host")?.asString ?: "127.0.0.1"
            val socksPort = root.get("socks_port")?.asInt ?: 1080
            val socksUser = root.get("socks_user")?.asString ?: ""
            val socksPass = root.get("socks_pass")?.asString ?: ""
            val googleHost = root.get("google_host")?.asString ?: "216.239.38.120"
            val sniJson = when {
                root.get("sni")?.isJsonArray == true -> Gson().toJson(root.getAsJsonArray("sni").mapNotNull { it.asString })
                root.get("sni")?.isJsonPrimitive == true -> Gson().toJson(listOf(root.get("sni").asString))
                else -> "[\"www.google.com\", \"mail.google.com\", \"accounts.google.com\"]"
            }
            val scriptKeysText = when {
                root.get("script_keys")?.isJsonArray == true -> {
                    root.getAsJsonArray("script_keys").mapNotNull { element ->
                        when {
                            element.isJsonObject -> {
                                val obj = element.asJsonObject
                                val id = obj.get("id")?.asString?.trim()
                                val account = obj.get("account")?.asString?.trim()
                                if (id.isNullOrBlank()) null
                                else if (account.isNullOrBlank()) id
                                else "$id|$account"
                            }
                            element.isJsonPrimitive -> element.asString.trim()
                            else -> null
                        }
                    }.filter { it.isNotBlank() }.joinToString("\n")
                }
                root.get("script_keys")?.isJsonPrimitive == true -> root.get("script_keys").asString.trim()
                else -> ""
            }
            val coalesceStepMs = root.get("coalesce_step_ms")?.asInt ?: 0
            val idleSlotsPerBucket = root.get("idle_slots_per_bucket")?.asInt?.coerceIn(1, 3) ?: 2
            val tunnelKey = root.get("tunnel_key")?.asString ?: ""

            ProfileEntity(
                name = name,
                debugTiming = debugTiming,
                socksHost = socksHost,
                socksPort = socksPort,
                socksUser = socksUser,
                socksPass = socksPass,
                googleHost = googleHost,
                sniJson = sniJson,
                scriptKeysText = scriptKeysText,
                tunnelKey = tunnelKey,
                coalesceStepMs = coalesceStepMs,
                idleSlotsPerBucket = idleSlotsPerBucket,
                remoteUrl = remoteUrl
            )
        } catch (_: Exception) {
            null
        }
    }
}

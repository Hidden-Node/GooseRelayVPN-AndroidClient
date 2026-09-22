package com.gooserelay.gooserelayvpn.ui.profiles

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
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
import java.io.IOException
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
            profileRepository.insertProfileAndSelectIfFirst(profile)
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
                    val code = connection.responseCode
                    if (code / 100 != 2) {
                        val errorBody = try {
                            connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
                        } catch (_: Exception) { "" }
                        throw IOException("HTTP $code: $errorBody")
                    }
                    connection.inputStream.bufferedReader().use { it.readText() }
                }

                val remoteProfile = parseProfileFromJson(json, remoteUrl = selectedProfile.remoteUrl)
                if (remoteProfile != null) {
                    val updated = selectedProfile.copy(
                        name = if (remoteProfile.name.isNotBlank()) remoteProfile.name else selectedProfile.name,
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
                    val code = connection.responseCode
                    if (code / 100 != 2) {
                        val errorBody = try {
                            connection.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) ?: ""
                        } catch (_: Exception) { "" }
                        throw IOException("HTTP $code: $errorBody")
                    }
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

    fun parseProfileFromJson(raw: String, defaultName: String? = null, remoteUrl: String? = null): ProfileEntity? =
        ProfileJsonParser.parse(raw, defaultName, remoteUrl)
}

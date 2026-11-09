package com.gooserelay.gooserelayvpn.ui.profiles

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gooserelay.gooserelayvpn.R
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.local.SubscriptionEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.data.repository.SubscriptionRepository
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
import com.gooserelay.gooserelayvpn.util.SubscriptionError
import com.gooserelay.gooserelayvpn.util.SubscriptionFetchException
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

@HiltViewModel
class ProfilesViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    private val subscriptionRepository: SubscriptionRepository
) : ViewModel() {

    val profiles: StateFlow<List<ProfileEntity>?> =
        profileRepository.getAllProfiles()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    val subscriptions: StateFlow<List<SubscriptionEntity>?> =
        subscriptionRepository.getAllSubscriptions()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    data class AddSubscriptionState(
        val visible: Boolean = false,
        val name: String = "",
        val url: String = "",
        val errorRes: Int? = null,
        val submitting: Boolean = false
    )

    private val _addSubscription = MutableStateFlow(AddSubscriptionState())
    val addSubscription: StateFlow<AddSubscriptionState> = _addSubscription.asStateFlow()

    private val _isUpdating = MutableStateFlow(false)
    val isUpdating: StateFlow<Boolean> = _isUpdating.asStateFlow()

    private var activeUpdates = 0

    private fun updateStarted() {
        activeUpdates++
        _isUpdating.value = true
    }

    private fun updateFinished() {
        activeUpdates = (activeUpdates - 1).coerceAtLeast(0)
        _isUpdating.value = activeUpdates > 0
    }

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

    fun openAddSubscriptionDialog(initialUrl: String = "") {
        _addSubscription.update {
            it.copy(visible = true, name = "", url = initialUrl, errorRes = null)
        }
    }

    fun dismissAddSubscriptionDialog() {
        _addSubscription.update { it.copy(visible = false) }
    }

    fun onSubscriptionNameChange(value: String) {
        _addSubscription.update { it.copy(name = value, errorRes = null) }
    }

    fun onSubscriptionUrlChange(value: String) {
        _addSubscription.update { it.copy(url = value, errorRes = null) }
    }

    fun submitSubscription(context: Context) {
        val state = _addSubscription.value
        val trimmedUrl = state.url.trim()
        if (!trimmedUrl.startsWith("http://", ignoreCase = true) &&
            !trimmedUrl.startsWith("https://", ignoreCase = true)
        ) {
            _addSubscription.update {
                it.copy(errorRes = R.string.subscriptions_error_invalid_url)
            }
            return
        }
        if (state.submitting) return
        _addSubscription.update { it.copy(submitting = true, errorRes = null) }
        updateStarted()
        viewModelScope.launch {
            try {
                val result = subscriptionRepository.add(
                    name = state.name.trim().ifBlank {
                        context.getString(R.string.subscriptions_default_name)
                    },
                    url = trimmedUrl
                )
                _addSubscription.update { it.copy(visible = false) }
                _updateMessage.value = if (result.created) {
                    context.getString(R.string.subscriptions_added)
                } else {
                    context.getString(R.string.subscriptions_updated, result.profilesApplied)
                }
            } catch (e: Exception) {
                val messageRes = subscriptionErrorRes(e)
                if (_addSubscription.value.visible) {
                    _addSubscription.update { it.copy(errorRes = messageRes) }
                } else {
                    // Dismissing keeps the request running; report the failure anyway.
                    _updateMessage.value = context.getString(messageRes)
                }
            } finally {
                _addSubscription.update { it.copy(submitting = false) }
                updateFinished()
            }
        }
    }

    fun refreshSubscription(context: Context, id: Long) {
        if (_isUpdating.value) return
        updateStarted()
        viewModelScope.launch {
            try {
                val applied = refreshOne(id)
                _updateMessage.value =
                    context.getString(R.string.subscriptions_updated, applied)
            } catch (e: Exception) {
                _updateMessage.value = context.getString(subscriptionErrorRes(e))
            } finally {
                updateFinished()
            }
        }
    }

    fun refreshAllSubscriptions(context: Context) {
        if (_isUpdating.value) return
        updateStarted()
        viewModelScope.launch {
            try {
                val snapshot = subscriptions.value.orEmpty()
                var successes = 0
                snapshot.forEach { subscription ->
                    try {
                        if (refreshOne(subscription.id) > 0) successes++
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        // One failing feed must not abort the whole sync.
                    }
                }
                _updateMessage.value = context.getString(
                    R.string.subscriptions_sync_summary,
                    successes,
                    snapshot.size
                )
            } finally {
                updateFinished()
            }
        }
    }

    fun deleteSubscription(id: Long) {
        viewModelScope.launch {
            subscriptionRepository.delete(id)
        }
    }

    private suspend fun refreshOne(id: Long): Int = subscriptionRepository.refresh(id)

    private fun subscriptionErrorRes(e: Exception): Int = when (e) {
        is SubscriptionFetchException -> when (e.error) {
            SubscriptionError.INVALID_URL,
            SubscriptionError.INSECURE_SCHEME -> R.string.subscriptions_error_invalid_url
            SubscriptionError.TOO_LARGE -> R.string.subscriptions_error_too_large
            SubscriptionError.EMPTY_FEED -> R.string.subscriptions_error_empty
            SubscriptionError.HTTP_ERROR -> R.string.subscriptions_error_http
            SubscriptionError.REDIRECT -> R.string.subscriptions_error_redirect
            SubscriptionError.NETWORK -> R.string.subscriptions_error_network
        }
        else -> R.string.subscriptions_error_network
    }

    fun importProfileFromUrl(url: String, context: Context) {
        viewModelScope.launch {
            updateStarted()
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

                val profile = parseProfileFromJson(json)
                if (profile != null) {
                    addProfile(profile)
                    _updateMessage.value = context.getString(R.string.profiles_update_success)
                } else {
                    _updateMessage.value = context.getString(R.string.profiles_import_failed_msg, context.getString(R.string.profiles_invalid_json_format))
                }
            } catch (e: Exception) {
                _updateMessage.value = context.getString(R.string.profiles_import_failed_msg, e.localizedMessage ?: context.getString(R.string.common_unknown_error))
            } finally {
                updateFinished()
            }
        }
    }

    fun clearUpdateMessage() {
        _updateMessage.value = null
    }

    fun importProfileFromUri(uri: Uri, resolver: ContentResolver, defaultName: String?, onInvalid: () -> Unit) {
        viewModelScope.launch {
            val profile = withContext(Dispatchers.IO) {
                val raw = resolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (raw.isBlank()) null else parseProfileFromJson(raw, defaultName)
            }
            if (profile != null) addProfile(profile) else onInvalid()
        }
    }

    fun exportProfileToFile(profile: ProfileEntity, uri: Uri, contentResolver: ContentResolver, onDone: () -> Unit) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { it.write(ConfigGenerator.exportProfileJson(profile)) }
            }
            onDone()
        }
    }

    fun parseProfileFromJson(raw: String, defaultName: String? = null): ProfileEntity? =
        ProfileJsonParser.parse(raw, defaultName)

    companion object {
        fun extractWebUrl(raw: String?): String? {
            if (raw.isNullOrBlank()) return null
            val trimmed = raw.trim()
            if (trimmed.startsWith("{") || trimmed.startsWith("goose-relay://", ignoreCase = true)) {
                return null
            }

            for (line in trimmed.lines()) {
                val lineTrimmed = line.trim()
                if (lineTrimmed.isEmpty()) continue
                val tokens = lineTrimmed.split(Regex("\\s+"))
                for (cand in tokens) {
                    val rawCand = if (cand.contains("](http://", ignoreCase = true) ||
                        cand.contains("](https://", ignoreCase = true)
                    ) {
                        val afterBracket = cand.substring(cand.indexOf("](") + 1)
                        if (afterBracket.startsWith("(")) {
                            var depth = 0
                            var endIdx = -1
                            for (i in afterBracket.indices) {
                                if (afterBracket[i] == '(') depth++
                                else if (afterBracket[i] == ')') {
                                    depth--
                                    if (depth == 0) {
                                        endIdx = i
                                        break
                                    }
                                }
                            }
                            if (endIdx != -1) afterBracket.substring(0, endIdx + 1) else afterBracket
                        } else {
                            afterBracket
                        }
                    } else {
                        cand
                    }
                    var cur = rawCand.trimEnd('.', ',', ';', ':')
                    while (cur.length >= 2) {
                        when {
                            cur.startsWith("(") && cur.endsWith(")") -> cur = cur.substring(1, cur.length - 1)
                            cur.startsWith("[") && cur.endsWith("]") -> cur = cur.substring(1, cur.length - 1)
                            cur.startsWith("<") && cur.endsWith(">") -> cur = cur.substring(1, cur.length - 1)
                            cur.startsWith("\"") && cur.endsWith("\"") -> cur = cur.substring(1, cur.length - 1)
                            cur.startsWith("'") && cur.endsWith("'") -> cur = cur.substring(1, cur.length - 1)
                            else -> break
                        }
                    }
                    val token = cur
                        .removePrefix("<").removePrefix("\"").removePrefix("'").removePrefix("[").removePrefix("(")
                        .trimEnd('.', ',', ';', ':', '>', '"', '\'', ']')

                    if ((token.startsWith("http://", ignoreCase = true) ||
                            token.startsWith("https://", ignoreCase = true)) &&
                        token.substringAfter("://").isNotBlank()
                    ) {
                        return token
                    }
                }
            }
            return null
        }
    }
}

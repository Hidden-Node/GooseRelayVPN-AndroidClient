package com.gooserelay.gooserelayvpn.ui.settings

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import com.gooserelay.gooserelayvpn.data.repository.ProfileRepository
import com.gooserelay.gooserelayvpn.util.ConfigGenerator
import com.gooserelay.gooserelayvpn.util.ProfileJsonParser
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val profileRepository: ProfileRepository,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val profileIdArg: Long? = savedStateHandle.get<String>("profileId")?.toLongOrNull()

    val selectedProfile: StateFlow<ProfileEntity?> = (
        if (profileIdArg != null) profileRepository.getProfileByIdFlow(profileIdArg)
        else profileRepository.getSelectedProfileFlow()
    ).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

    fun exportConfigJson(profile: ProfileEntity): String = ConfigGenerator.exportProfileJson(profile)

    fun saveProfile(profile: ProfileEntity) {
        viewModelScope.launch { profileRepository.updateProfile(profile) }
    }

    fun importJsonToProfile(profile: ProfileEntity, json: String): ProfileEntity? =
        ProfileJsonParser.mergeInto(profile, json)
}

package com.gooserelay.gooserelayvpn.data.repository

import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProfileRepository @Inject constructor(
    private val profileDao: ProfileDao
) {
    fun getAllProfiles(): Flow<List<ProfileEntity>> = profileDao.getAllProfiles()

    fun getSelectedProfileFlow(): Flow<ProfileEntity?> = profileDao.getSelectedProfileFlow()

    suspend fun getSelectedProfile(): ProfileEntity? = profileDao.getSelectedProfile()

    suspend fun getProfileById(id: Long): ProfileEntity? = profileDao.getProfileById(id)
    fun getProfileByIdFlow(id: Long): Flow<ProfileEntity?> = profileDao.getProfileByIdFlow(id)

    suspend fun insertProfile(profile: ProfileEntity): Long = profileDao.insertProfile(profile)

    suspend fun updateProfile(profile: ProfileEntity) = profileDao.updateProfile(profile)

    suspend fun insertProfileAndSelectIfFirst(profile: ProfileEntity): Long =
        profileDao.insertProfileAndSelectIfFirst(profile)

    suspend fun deleteProfile(profile: ProfileEntity) =
        profileDao.deleteProfileAndReselect(profile)

    suspend fun setSelectedProfile(id: Long) = profileDao.setSelectedProfile(id)
}

package com.gooserelay.gooserelayvpn.data.local

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ProfileDao {
    @Query("SELECT * FROM profiles ORDER BY createdAt DESC")
    fun getAllProfiles(): Flow<List<ProfileEntity>>

    @Query("SELECT * FROM profiles WHERE id = :id")
    suspend fun getProfileById(id: Long): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    fun getProfileByIdFlow(id: Long): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    suspend fun getSelectedProfile(): ProfileEntity?

    @Query("SELECT * FROM profiles WHERE isSelected = 1 LIMIT 1")
    fun getSelectedProfileFlow(): Flow<ProfileEntity?>

    @Query("SELECT * FROM profiles ORDER BY createdAt DESC LIMIT 1")
    suspend fun getNewestProfile(): ProfileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: ProfileEntity): Long

    @Update
    suspend fun updateProfile(profile: ProfileEntity)

    @Delete
    suspend fun deleteProfile(profile: ProfileEntity)

    @Query("UPDATE profiles SET isSelected = 0")
    suspend fun deselectAll()

    @Query("UPDATE profiles SET isSelected = 1 WHERE id = :id")
    suspend fun selectProfile(id: Long)

    @Query("SELECT COUNT(*) FROM profiles")
    suspend fun countProfiles(): Int

    @Transaction
    suspend fun setSelectedProfile(id: Long) {
        deselectAll()
        selectProfile(id)
    }

    /**
     * Inserts the profile and auto-selects it only when it is the first one in
     * the table. Decision is made from DB state inside one transaction, never
     * from ViewModel state.
     */
    @Transaction
    suspend fun insertProfileAndSelectIfFirst(profile: ProfileEntity): Long {
        val id = insertProfile(profile.copy(isSelected = false))
        if (countProfiles() == 1) {
            selectProfile(id)
        }
        return id
    }

    /**
     * Deletes the profile and, if it was the selected one, atomically re-selects
     * the newest remaining profile. Never leaves the table with zero selected
     * rows (when at least one row remains).
     */
    @Transaction
    suspend fun deleteProfileAndReselect(profile: ProfileEntity) {
        val selectedId = getSelectedProfile()?.id
        deleteProfile(profile)
        if (selectedId != null && selectedId == profile.id) {
            getNewestProfile()?.let { remaining ->
                deselectAll()
                selectProfile(remaining.id)
            }
        }
    }
}

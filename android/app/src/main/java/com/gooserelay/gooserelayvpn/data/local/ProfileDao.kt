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

    @Query("SELECT COUNT(*) FROM subscriptions WHERE id = :subId")
    suspend fun countSubscriptions(subId: Long): Int

    @Query("DELETE FROM profiles WHERE subscriptionId = :subId")
    suspend fun deleteProfilesBySubscription(subId: Long)

    /**
     * Replaces one subscription's profiles atomically. Returns false without
     * touching anything when the subscription row no longer exists (the user
     * deleted it while the download was in flight).
     */
    @Transaction
    suspend fun replaceProfilesForSubscription(
        subId: Long,
        newProfiles: List<ProfileEntity>
    ): Boolean {
        if (countSubscriptions(subId) == 0) return false
        val previouslySelected = getSelectedProfile()
        val activeBelongsToSub = previouslySelected?.subscriptionId == subId

        deleteProfilesBySubscription(subId)
        // Descending timestamps keep feed order stable under ORDER BY
        // createdAt DESC; profiles parsed in the same millisecond would tie.
        val baseCreatedAt = System.currentTimeMillis()
        val insertedIds = newProfiles.mapIndexed { index, profile ->
            insertProfile(
                profile.copy(
                    id = 0,
                    subscriptionId = subId,
                    isSelected = false,
                    createdAt = baseCreatedAt - index
                )
            )
        }

        if (activeBelongsToSub) {
            // Match by name only: socksHost is the local SOCKS listener and
            // defaults to 127.0.0.1 for every profile, so matching on it selects
            // the first feed entry instead of the active node.
            val matchIndex = newProfiles.indexOfFirst {
                it.name == previouslySelected?.name
            }
            val targetId = insertedIds.getOrNull(matchIndex)
                ?: insertedIds.firstOrNull()
                ?: getNewestProfile()?.id
            if (targetId != null) setSelectedProfile(targetId)
        } else if (getSelectedProfile() == null) {
            getNewestProfile()?.id?.let { setSelectedProfile(it) }
        }
        return true
    }

    @Query("DELETE FROM subscriptions WHERE id = :subId")
    suspend fun deleteSubscriptionRow(subId: Long)

    /**
     * Deletes a subscription and all of its profiles in one transaction, so a
     * concurrent refresh can never re-insert profiles for a deleted
     * subscription. Keeps the selection invariant.
     */
    @Transaction
    suspend fun deleteSubscriptionCascade(subId: Long) {
        val selectedBelongs = getSelectedProfile()?.subscriptionId == subId
        deleteProfilesBySubscription(subId)
        deleteSubscriptionRow(subId)
        if (selectedBelongs) {
            getNewestProfile()?.id?.let { setSelectedProfile(it) }
        }
    }
}

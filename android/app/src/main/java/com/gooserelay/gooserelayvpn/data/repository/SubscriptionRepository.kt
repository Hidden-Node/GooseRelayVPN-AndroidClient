package com.gooserelay.gooserelayvpn.data.repository

import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.SubscriptionDao
import com.gooserelay.gooserelayvpn.data.local.SubscriptionEntity
import com.gooserelay.gooserelayvpn.util.SubscriptionFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Subscription URLs commonly embed per-user tokens: treat them as credentials
 * and never log them.
 */
@Singleton
class SubscriptionRepository @Inject constructor(
    private val subscriptionDao: SubscriptionDao,
    private val profileDao: ProfileDao
) {
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>> =
        subscriptionDao.getAllSubscriptions()

    data class AddResult(val created: Boolean, val profilesApplied: Int)

    /**
     * Adds a new subscription, or refreshes it when the URL already exists.
     * Returns whether a row was created and how many profiles were applied.
     */
    suspend fun add(name: String, url: String): AddResult {
        val existing = subscriptionDao.getSubscriptionByUrl(url)
        if (existing != null) {
            return AddResult(created = false, profilesApplied = refresh(existing.id))
        }
        // Fetch before inserting so a failed first download leaves no empty row.
        val result = withContext(Dispatchers.IO) { SubscriptionFetcher.fetch(url) }
        val id = subscriptionDao.insertSubscription(
            SubscriptionEntity(
                name = name,
                url = url,
                lastUpdated = System.currentTimeMillis()
            )
        )
        profileDao.replaceProfilesForSubscription(id, result.profiles)
        return AddResult(created = true, profilesApplied = result.profiles.size)
    }

    /** Returns the number of profiles applied; 0 when the subscription is gone. */
    suspend fun refresh(id: Long): Int {
        val subscription = subscriptionDao.getSubscriptionById(id) ?: return 0
        val result = withContext(Dispatchers.IO) {
            SubscriptionFetcher.fetch(subscription.url)
        }
        val applied = profileDao.replaceProfilesForSubscription(id, result.profiles)
        if (!applied) return 0
        subscriptionDao.updateSubscription(
            subscription.copy(lastUpdated = System.currentTimeMillis())
        )
        return result.profiles.size
    }

    suspend fun delete(id: Long) {
        // One transaction: deleting the profiles and the row separately
        // lets a concurrent refresh insert orphan profiles in between.
        profileDao.deleteSubscriptionCascade(id)
    }
}

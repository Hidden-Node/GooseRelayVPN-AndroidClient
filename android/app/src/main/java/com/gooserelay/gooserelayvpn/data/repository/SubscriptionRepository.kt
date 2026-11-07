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

    /** Adds a new subscription, or refreshes it when the URL already exists. */
    suspend fun add(name: String, url: String): Long {
        val existing = subscriptionDao.getSubscriptionByUrl(url)
        if (existing != null) {
            refresh(existing.id)
            return existing.id
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
        return id
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

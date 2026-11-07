package com.gooserelay.gooserelayvpn.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY lastUpdated DESC")
    fun getAllSubscriptions(): Flow<List<SubscriptionEntity>>

    @Query("SELECT * FROM subscriptions WHERE id = :id")
    suspend fun getSubscriptionById(id: Long): SubscriptionEntity?

    @Query("SELECT * FROM subscriptions WHERE url = :url LIMIT 1")
    suspend fun getSubscriptionByUrl(url: String): SubscriptionEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSubscription(sub: SubscriptionEntity): Long

    @Update
    suspend fun updateSubscription(sub: SubscriptionEntity)
}

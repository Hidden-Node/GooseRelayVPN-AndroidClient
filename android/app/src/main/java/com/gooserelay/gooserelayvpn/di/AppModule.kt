package com.gooserelay.gooserelayvpn.di

import android.content.Context
import com.gooserelay.gooserelayvpn.data.local.AppDatabase
import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.SubscriptionDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase {
        return AppDatabase.getInstance(context)
    }

    @Provides
    fun provideProfileDao(database: AppDatabase): ProfileDao {
        return database.profileDao()
    }

    @Provides
    fun provideSubscriptionDao(database: AppDatabase): SubscriptionDao {
        return database.subscriptionDao()
    }
}

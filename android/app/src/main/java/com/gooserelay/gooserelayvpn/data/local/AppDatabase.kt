package com.gooserelay.gooserelayvpn.data.local

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.DeleteColumn
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec

/**
 * Schema change policy:
 *
 * - Additive-only changes (new nullable column, new column with a default,
 *   new table): bump [version] and declare `AutoMigration(from = N,
 *   to = N + 1)` in the annotation below. Room generates and validates the
 *   SQL from the exported schemas — do not hand-write it.
 *
 * ```
 * @Database(
 *     entities = [ProfileEntity::class],
 *     version = 5,
 *     exportSchema = true,
 *     autoMigrations = [AutoMigration(from = 4, to = 5)]
 * )
 * ```
 *
 * - Destructive changes (drop/rename/rebuild or any data transformation):
 *   keep a manual Migration in ProfileMigrations, wrapped in
 *   ProfileMigrations.SafetyExportMigration so a JSON snapshot of the rows is
 *   written first, and register it via addMigrations in getInstance.
 *
 * Manual migrations always win over an AutoMigration declared for the same
 * versions. Remember to commit the new schemas/(N+1).json that KSP emits.
 */
@Database(
    entities = [ProfileEntity::class, SubscriptionEntity::class],
    version = 6,
    exportSchema = true,
    autoMigrations = [
        AutoMigration(from = 4, to = 5),
        AutoMigration(from = 5, to = 6, spec = AppDatabase.Schema5to6::class)
    ]
)
abstract class AppDatabase : RoomDatabase() {
    @DeleteColumn(tableName = "profiles", columnName = "remoteUrl")
    class Schema5to6 : AutoMigrationSpec

    abstract fun profileDao(): ProfileDao
    abstract fun subscriptionDao(): SubscriptionDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "gooserelay_vpn.db"
                )
                    .addMigrations(*ProfileMigrations.ALL)
                    // Downgrading the app (e.g. rolling back an update) would
                    // otherwise crash on every launch; profiles are cheap to
                    // recreate, a permanently crashing app is not.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build().also { INSTANCE = it }
            }
        }
    }
}

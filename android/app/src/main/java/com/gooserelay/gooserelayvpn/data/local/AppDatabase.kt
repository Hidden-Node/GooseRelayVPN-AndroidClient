package com.gooserelay.gooserelayvpn.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
@Database(entities = [ProfileEntity::class], version = 4, exportSchema = true)
abstract class AppDatabase : RoomDatabase() {
    abstract fun profileDao(): ProfileDao

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

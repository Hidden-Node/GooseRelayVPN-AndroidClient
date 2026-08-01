package com.gooserelay.gooserelayvpn.data.local

import android.content.Context
import androidx.room.migration.Migration
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File

/**
 * Central place for Room migrations. Each Migration(N, N+1) lives here.
 *
 * Convention: every migration that risks user data MUST be preceded by
 * an on-upgrade export of the current profile table to
 * `cacheDir/profiles_backup_<fromVersion>_to_<toVersion>_<timestamp>.json`
 * before the schema change. See [SafetyExportMigration].
 */
object ProfileMigrations {

    private val gson = Gson()

    /**
     * Run before [delegate] applies its schema change. Reads every profile
     * from the v`from` schema and writes a JSON snapshot to the app's cache
     * directory. If the export fails, the migration is NOT applied and an
     * [IllegalStateException] is thrown so the user sees a crash instead
     * of silent data loss.
     */
    class SafetyExportMigration(
        private val context: Context,
        private val from: Int,
        private val to: Int,
        private val delegate: Migration
    ) : Migration(from, to) {

        override fun migrate(db: SupportSQLiteDatabase) {
            exportCurrentProfiles(db)
            delegate.migrate(db)
        }

        private fun exportCurrentProfiles(db: SupportSQLiteDatabase) {
            val cursor = db.query("SELECT * FROM profiles")
            val rows = mutableListOf<JsonObject>()
            cursor.use { c ->
                while (c.moveToNext()) {
                    val obj = JsonObject()
                    // Column order matches ProfileEntity field order at v3.
                    // Use column names defensively — older schema snapshots
                    // may differ.
                    fun col(name: String): String =
                        c.getString(c.getColumnIndexOrThrow(name))
                    obj.apply {
                        addProperty("id", c.getLong(c.getColumnIndexOrThrow("id")))
                        addProperty("name", col("name"))
                        addProperty("debugTiming", c.getInt(c.getColumnIndexOrThrow("debugTiming")) != 0)
                        addProperty("socksHost", col("socksHost"))
                        addProperty("socksPort", c.getInt(c.getColumnIndexOrThrow("socksPort")))
                        addProperty("socksUser", col("socksUser"))
                        addProperty("socksPass", col("socksPass"))
                        addProperty("googleHost", col("googleHost"))
                        addProperty("sniJson", col("sniJson"))
                        addProperty("scriptKeysText", col("scriptKeysText"))
                        addProperty("tunnelKey", col("tunnelKey"))
                        addProperty("coalesceStepMs", c.getInt(c.getColumnIndexOrThrow("coalesceStepMs")))
                        addProperty("idleSlotsPerBucket", c.getInt(c.getColumnIndexOrThrow("idleSlotsPerBucket")))
                        addProperty("remoteUrl", if (c.isNull(c.getColumnIndexOrThrow("remoteUrl"))) null else col("remoteUrl"))
                        addProperty("isSelected", c.getInt(c.getColumnIndexOrThrow("isSelected")) != 0)
                        addProperty("createdAt", c.getLong(c.getColumnIndexOrThrow("createdAt")))
                    }
                    rows.add(obj)
                }
            }
            val snapshot = JsonObject().apply {
                addProperty("schemaVersion", from)
                add("profiles", gson.toJsonTree(rows))
            }
            val backupFile = File(
                context.cacheDir,
                "profiles_backup_v${from}_to_v${to}_${System.currentTimeMillis()}.json"
            )
            backupFile.writeText(gson.toJson(snapshot))
        }
    }

    private fun existingColumns(db: SupportSQLiteDatabase): Set<String> {
        val columns = mutableSetOf<String>()
        db.query("PRAGMA table_info(profiles)").use { c ->
            val nameIdx = c.getColumnIndex("name")
            while (c.moveToNext()) columns.add(c.getString(nameIdx))
        }
        return columns
    }

    internal fun migrationSql2To3(existing: Set<String>): List<String> =
        listOf(
            "socksUser" to "ALTER TABLE profiles ADD COLUMN socksUser TEXT NOT NULL DEFAULT ''",
            "socksPass" to "ALTER TABLE profiles ADD COLUMN socksPass TEXT NOT NULL DEFAULT ''"
        ).filterNot { it.first in existing }.map { it.second }

    internal fun migrationSql3To4(existing: Set<String>): List<String> =
        listOf(
            "coalesceStepMs" to "ALTER TABLE profiles ADD COLUMN coalesceStepMs INTEGER NOT NULL DEFAULT 0",
            "idleSlotsPerBucket" to "ALTER TABLE profiles ADD COLUMN idleSlotsPerBucket INTEGER NOT NULL DEFAULT 2",
            "remoteUrl" to "ALTER TABLE profiles ADD COLUMN remoteUrl TEXT"
        ).filterNot { it.first in existing }.map { it.second }

    val MIGRATION_2_3 = object : Migration(2, 3) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrationSql2To3(existingColumns(db)).forEach { db.execSQL(it) }
        }
    }

    val MIGRATION_3_4 = object : Migration(3, 4) {
        override fun migrate(db: SupportSQLiteDatabase) {
            migrationSql3To4(existingColumns(db)).forEach { db.execSQL(it) }
        }
    }

    val ALL: Array<Migration> = arrayOf(MIGRATION_2_3, MIGRATION_3_4)
}

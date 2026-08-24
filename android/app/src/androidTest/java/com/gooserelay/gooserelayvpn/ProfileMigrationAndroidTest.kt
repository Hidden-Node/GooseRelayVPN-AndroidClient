package com.gooserelay.gooserelayvpn

import android.database.Cursor
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SimpleSQLiteQuery
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.AppDatabase
import com.gooserelay.gooserelayvpn.data.local.ProfileMigrations
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs the real migrations through Room's MigrationTestHelper so the migrated
 * schema is validated against the exported Room schemas (the JVM-only tests
 * in ProfileMigrationsTest only exercise the raw SQL strings).
 */
@RunWith(AndroidJUnit4::class)
class ProfileMigrationAndroidTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        AppDatabase::class.java
    )

    @Test
    fun migrate2To4_validatesSchema_andPreservesRows() {
        val created = helper.createDatabase(DB_2_TO_4, 2)
        created.execSQL(
            "INSERT INTO profiles (name, debugTiming, socksHost, socksPort, googleHost, " +
                "sniJson, scriptKeysText, tunnelKey, isSelected, createdAt) " +
                "VALUES ('x', 1, '10.0.0.1', 1080, '216.239.38.120', '[\"www.google.com\"]', 'sk', 'k', 1, 42)"
        )
        created.close()

        val db = helper.runMigrationsAndValidate(
            DB_2_TO_4, LATEST_VERSION, true,
            ProfileMigrations.MIGRATION_2_3,
            ProfileMigrations.MIGRATION_3_4
        )

        val c: Cursor = db.query(
            SimpleSQLiteQuery(
                "SELECT name, socksHost, socksPort, socksUser, socksPass, tunnelKey, " +
                    "coalesceStepMs, idleSlotsPerBucket, remoteUrl, isSelected, createdAt " +
                    "FROM profiles"
            )
        )
        try {
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("x")
            assertThat(c.getString(1)).isEqualTo("10.0.0.1")
            assertThat(c.getInt(2)).isEqualTo(1080)
            assertThat(c.getString(3)).isEmpty()
            assertThat(c.getString(4)).isEmpty()
            assertThat(c.getString(5)).isEqualTo("k")
            assertThat(c.getInt(6)).isEqualTo(0)
            assertThat(c.getInt(7)).isEqualTo(2)
            assertThat(c.isNull(8)).isTrue()
            assertThat(c.getInt(9)).isEqualTo(1)
            assertThat(c.getLong(10)).isEqualTo(42L)
            assertThat(c.moveToNext()).isFalse()
        } finally {
            c.close()
        }
        db.close()
    }

    @Test
    fun migrate3To4_addsRemoteUrl_andValidatesSchema() {
        val created = helper.createDatabase(DB_3_TO_4, 3)
        created.execSQL(
            "INSERT INTO profiles (name, debugTiming, socksHost, socksPort, socksUser, socksPass, " +
                "googleHost, sniJson, scriptKeysText, tunnelKey, coalesceStepMs, idleSlotsPerBucket, " +
                "isSelected, createdAt) " +
                "VALUES ('y', 0, '127.0.0.1', 1081, 'u', 'p', '216.239.38.120', '[]', '', 'k2', 5, 3, 1, 7)"
        )
        created.close()

        val db = helper.runMigrationsAndValidate(
            DB_3_TO_4, LATEST_VERSION, true,
            ProfileMigrations.MIGRATION_3_4
        )

        db.query(
            SimpleSQLiteQuery(
                "SELECT name, socksUser, socksPass, tunnelKey, coalesceStepMs, " +
                    "idleSlotsPerBucket, remoteUrl FROM profiles"
            )
        ).use { c ->
            assertThat(c.moveToFirst()).isTrue()
            assertThat(c.getString(0)).isEqualTo("y")
            assertThat(c.getString(1)).isEqualTo("u")
            assertThat(c.getString(2)).isEqualTo("p")
            assertThat(c.getString(3)).isEqualTo("k2")
            assertThat(c.getInt(4)).isEqualTo(5)
            assertThat(c.getInt(5)).isEqualTo(3)
            assertThat(c.isNull(6)).isTrue()
        }
        db.close()
    }

    @Test
    fun migratedDb_opensThroughRoomDao() {
        val created = helper.createDatabase(DB_ROOM_OPEN, 2)
        created.execSQL(
            "INSERT INTO profiles (name, isSelected, createdAt) VALUES ('z', 1, 99)"
        )
        created.close()

        val db = helper.runMigrationsAndValidate(
            DB_ROOM_OPEN, LATEST_VERSION, true,
            *ProfileMigrations.ALL
        )
        db.close()

        // Opening through a real Room instance proves the identity hash and
        // entity mapping match after migration (the runtime crash scenario).
        val room = Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            AppDatabase::class.java,
            DB_ROOM_OPEN
        ).build()
        try {
            room.openHelper.writableDatabase
        } finally {
            room.close()
        }
    }

    private companion object {
        const val DB_2_TO_4 = "profile-migration-test-2to4.db"
        const val DB_3_TO_4 = "profile-migration-test-3to4.db"
        const val DB_ROOM_OPEN = "profile-migration-test-room-open.db"
        const val LATEST_VERSION = 4
    }
}

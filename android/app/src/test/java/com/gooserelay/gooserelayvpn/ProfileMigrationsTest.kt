package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileMigrations
import org.json.JSONObject
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

class ProfileMigrationsTest {

    private fun openMemoryDb(): Connection =
        DriverManager.getConnection("jdbc:sqlite::memory:")

    private fun existingColumns(conn: Connection): Set<String> {
        val columns = mutableSetOf<String>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name FROM pragma_table_info('profiles')").use { rs ->
                while (rs.next()) columns.add(rs.getString(1))
            }
        }
        return columns
    }

    private fun columnTypes(conn: Connection): Map<String, Pair<String, Int>> {
        val info = mutableMapOf<String, Pair<String, Int>>()
        conn.createStatement().use { st ->
            st.executeQuery("SELECT name, type, \"notnull\" FROM pragma_table_info('profiles')").use { rs ->
                while (rs.next()) info[rs.getString(1)] = rs.getString(2) to rs.getInt(3)
            }
        }
        return info
    }

    private fun exec(conn: Connection, sql: String) {
        conn.createStatement().use { it.execute(sql) }
    }

    @Test
    fun `registers exactly migrations 2 to 3 and 3 to 4`() {
        val ranges = ProfileMigrations.ALL.map { it.startVersion to it.endVersion }
        assertThat(ranges).containsExactly(2 to 3, 3 to 4).inOrder()
    }

    @Test
    fun `v2 table migrates to all 16 columns preserving data`() {
        val schemaJson = File("schemas/com.gooserelay.gooserelayvpn.data.local.AppDatabase/2.json")
            .readText()
        val createSql = JSONObject(schemaJson)
            .getJSONObject("database")
            .getJSONArray("entities")
            .getJSONObject(0)
            .getString("createSql")
            .replace("\${TABLE_NAME}", "profiles")

        openMemoryDb().use { conn ->
            exec(conn, createSql)
            exec(
                conn,
                "INSERT INTO profiles (name, debugTiming, socksHost, socksPort, googleHost, " +
                    "sniJson, scriptKeysText, tunnelKey, isSelected, createdAt) " +
                    "VALUES ('x', 0, '127.0.0.1', 1080, '216.239.38.120', '[]', '', 'k', 0, 1)"
            )

            ProfileMigrations.migrationSql2To3(existingColumns(conn)).forEach { exec(conn, it) }
            ProfileMigrations.migrationSql3To4(existingColumns(conn)).forEach { exec(conn, it) }

            val info = columnTypes(conn)
            assertThat(info.keys).containsExactly(
                "id", "name", "debugTiming", "socksHost", "socksPort", "socksUser", "socksPass",
                "googleHost", "sniJson", "scriptKeysText", "tunnelKey", "coalesceStepMs",
                "idleSlotsPerBucket", "remoteUrl", "isSelected", "createdAt"
            )
            assertThat(info["socksUser"]).isEqualTo("TEXT" to 1)
            assertThat(info["socksPass"]).isEqualTo("TEXT" to 1)
            assertThat(info["coalesceStepMs"]).isEqualTo("INTEGER" to 1)
            assertThat(info["idleSlotsPerBucket"]).isEqualTo("INTEGER" to 1)
            assertThat(info["remoteUrl"]).isEqualTo("TEXT" to 0)

            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT name, debugTiming, socksHost, socksPort, googleHost, " +
                        "sniJson, scriptKeysText, tunnelKey, isSelected, createdAt FROM profiles"
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("x")
                    assertThat(rs.getInt(2)).isEqualTo(0)
                    assertThat(rs.getString(3)).isEqualTo("127.0.0.1")
                    assertThat(rs.getInt(4)).isEqualTo(1080)
                    assertThat(rs.getString(5)).isEqualTo("216.239.38.120")
                    assertThat(rs.getString(6)).isEqualTo("[]")
                    assertThat(rs.getString(7)).isEqualTo("")
                    assertThat(rs.getString(8)).isEqualTo("k")
                    assertThat(rs.getInt(9)).isEqualTo(0)
                    assertThat(rs.getLong(10)).isEqualTo(1L)
                    assertThat(rs.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `v3 fifteen column table migrates to v4 preserving data`() {
        openMemoryDb().use { conn ->
            exec(
                conn,
                "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `debugTiming` INTEGER NOT NULL, `socksHost` TEXT NOT NULL, " +
                    "`socksPort` INTEGER NOT NULL, `socksUser` TEXT NOT NULL, `socksPass` TEXT NOT NULL, " +
                    "`googleHost` TEXT NOT NULL, `sniJson` TEXT NOT NULL, `scriptKeysText` TEXT NOT NULL, " +
                    "`tunnelKey` TEXT NOT NULL, `coalesceStepMs` INTEGER NOT NULL, " +
                    "`idleSlotsPerBucket` INTEGER NOT NULL, `isSelected` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
            exec(
                conn,
                "INSERT INTO profiles (name, debugTiming, socksHost, socksPort, socksUser, socksPass, " +
                    "googleHost, sniJson, scriptKeysText, tunnelKey, coalesceStepMs, idleSlotsPerBucket, " +
                    "isSelected, createdAt) " +
                    "VALUES ('y', 1, '10.0.0.1', 1081, 'u', 'p', '216.239.38.120', '[]', 'sk', 'k2', 0, 2, 1, 2)"
            )

            val before = existingColumns(conn)
            ProfileMigrations.migrationSql3To4(before).forEach { exec(conn, it) }

            val after = existingColumns(conn)
            assertThat(after - before).containsExactly("remoteUrl")
            assertThat(after).containsExactly(
                "id", "name", "debugTiming", "socksHost", "socksPort", "socksUser", "socksPass",
                "googleHost", "sniJson", "scriptKeysText", "tunnelKey", "coalesceStepMs",
                "idleSlotsPerBucket", "remoteUrl", "isSelected", "createdAt"
            )

            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT name, socksUser, socksPass, tunnelKey, coalesceStepMs, " +
                        "idleSlotsPerBucket FROM profiles"
                ).use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("y")
                    assertThat(rs.getString(2)).isEqualTo("u")
                    assertThat(rs.getString(3)).isEqualTo("p")
                    assertThat(rs.getString(4)).isEqualTo("k2")
                    assertThat(rs.getInt(5)).isEqualTo(0)
                    assertThat(rs.getInt(6)).isEqualTo(2)
                    assertThat(rs.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `v3 thirteen column table migrates without duplicating socksUser and socksPass`() {
        openMemoryDb().use { conn ->
            exec(
                conn,
                "CREATE TABLE IF NOT EXISTS `profiles` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                    "`name` TEXT NOT NULL, `debugTiming` INTEGER NOT NULL, `socksHost` TEXT NOT NULL, " +
                    "`socksPort` INTEGER NOT NULL, `socksUser` TEXT NOT NULL, `socksPass` TEXT NOT NULL, " +
                    "`googleHost` TEXT NOT NULL, `sniJson` TEXT NOT NULL, `scriptKeysText` TEXT NOT NULL, " +
                    "`tunnelKey` TEXT NOT NULL, `isSelected` INTEGER NOT NULL, " +
                    "`createdAt` INTEGER NOT NULL)"
            )
            exec(
                conn,
                "INSERT INTO profiles (name, debugTiming, socksHost, socksPort, socksUser, socksPass, " +
                    "googleHost, sniJson, scriptKeysText, tunnelKey, isSelected, createdAt) " +
                    "VALUES ('z', 0, '127.0.0.1', 1080, 'u2', 'p2', '216.239.38.120', '[]', '', 'k3', 0, 3)"
            )

            val before = existingColumns(conn)
            ProfileMigrations.migrationSql3To4(before).forEach { exec(conn, it) }

            val after = existingColumns(conn)
            assertThat(after - before).containsExactly(
                "coalesceStepMs", "idleSlotsPerBucket", "remoteUrl"
            )
            assertThat(after.count { it == "socksUser" }).isEqualTo(1)
            assertThat(after.count { it == "socksPass" }).isEqualTo(1)

            conn.createStatement().use { st ->
                st.executeQuery("SELECT name, socksUser, socksPass, tunnelKey FROM profiles").use { rs ->
                    assertThat(rs.next()).isTrue()
                    assertThat(rs.getString(1)).isEqualTo("z")
                    assertThat(rs.getString(2)).isEqualTo("u2")
                    assertThat(rs.getString(3)).isEqualTo("p2")
                    assertThat(rs.getString(4)).isEqualTo("k3")
                    assertThat(rs.next()).isFalse()
                }
            }
        }
    }

    @Test
    fun `migrationSql3To4 is a no-op on the full v4 column set`() {
        val full16 = setOf(
            "id", "name", "debugTiming", "socksHost", "socksPort", "socksUser", "socksPass",
            "googleHost", "sniJson", "scriptKeysText", "tunnelKey", "coalesceStepMs",
            "idleSlotsPerBucket", "remoteUrl", "isSelected", "createdAt"
        )
        assertThat(ProfileMigrations.migrationSql3To4(full16)).isEmpty()
    }
}

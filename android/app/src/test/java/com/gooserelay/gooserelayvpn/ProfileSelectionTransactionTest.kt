package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.data.local.ProfileDao
import com.gooserelay.gooserelayvpn.data.local.ProfileEntity
import kotlinx.coroutines.test.runTest
import org.junit.Test

/** Fake DAO recording state in memory; default @Transaction methods run for real. */
private class FakeProfileDao : ProfileDao {
    val rows = LinkedHashMap<Long, ProfileEntity>()
    private var nextId = 1L
    var subscriptionCount = 1

    override fun getAllProfiles() = throw UnsupportedOperationException()
    override suspend fun getProfileById(id: Long) = rows[id]
    override fun getProfileByIdFlow(id: Long) = throw UnsupportedOperationException()
    override suspend fun getSelectedProfile() = rows.values.firstOrNull { it.isSelected }
    override fun getSelectedProfileFlow() = throw UnsupportedOperationException()
    override suspend fun getNewestProfile() = rows.values.maxByOrNull { it.createdAt }
    override suspend fun insertProfile(profile: ProfileEntity): Long {
        val id = nextId++
        rows[id] = profile.copy(id = id)
        return id
    }
    override suspend fun updateProfile(profile: ProfileEntity) { rows[profile.id] = profile }
    override suspend fun deleteProfile(profile: ProfileEntity) { rows.remove(profile.id) }
    override suspend fun deselectAll() {
        rows.replaceAll { _, p -> p.copy(isSelected = false) }
    }
    override suspend fun selectProfile(id: Long) {
        rows[id] = rows.getValue(id).copy(isSelected = true)
    }
    override suspend fun countProfiles() = rows.size
    override suspend fun countSubscriptions(subId: Long) = subscriptionCount
    override suspend fun deleteProfilesBySubscription(subId: Long) {
        rows.entries.removeIf { it.value.subscriptionId == subId }
    }
    override suspend fun deleteSubscriptionRow(subId: Long) {}
    // insertProfileAndSelectIfFirst, deleteProfileAndReselect:
    // default implementations from the interface — do NOT override.
}

class ProfileSelectionTransactionTest {

    @Test
    fun `first insert is auto-selected, later inserts are not`() = runTest {
        val dao = FakeProfileDao()
        val first = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        val second = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b"))
        assertThat(dao.rows.getValue(first).isSelected).isTrue()
        assertThat(dao.rows.getValue(second).isSelected).isFalse()
    }

    @Test
    fun `inserting when another profile is already selected does not steal selection`() = runTest {
        val dao = FakeProfileDao()
        val first = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        dao.setSelectedProfile(first)
        dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b"))
        assertThat(dao.rows.getValue(first).isSelected).isTrue()
    }

    @Test
    fun `deleting the selected profile re-selects the newest remaining`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        val b = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b", createdAt = 2))
        dao.setSelectedProfile(a)
        dao.deleteProfileAndReselect(dao.rows.getValue(a))
        assertThat(dao.rows.getValue(b).isSelected).isTrue()
    }

    @Test
    fun `deleting a non-selected profile leaves selection untouched`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        val b = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b", createdAt = 2))
        dao.setSelectedProfile(a)
        dao.deleteProfileAndReselect(dao.rows.getValue(b))
        assertThat(dao.rows.getValue(a).isSelected).isTrue()
    }

    @Test
    fun `deleting the only profile leaves table empty without crash`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        dao.deleteProfileAndReselect(dao.rows.getValue(a))
        assertThat(dao.rows).isEmpty()
    }

    @Test
    fun `deleting with a stale unselected entity still re-selects the newest remaining`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        val b = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b", createdAt = 2))
        dao.setSelectedProfile(a)
        // Caller holds a pre-select copy: entity says isSelected = false,
        // but the DB has a selected.
        val stale = dao.rows.getValue(a).copy(isSelected = false)
        dao.deleteProfileAndReselect(stale)
        assertThat(dao.rows.getValue(b).isSelected).isTrue()
    }

    @Test
    fun `stale selected flag on a non-selected profile does not steal selection`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a", createdAt = 1))
        val b = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b", createdAt = 2))
        val c = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "c", createdAt = 3))
        dao.setSelectedProfile(a)
        // Caller holds a copy of c claiming isSelected = true, but the DB has a selected.
        val stale = dao.rows.getValue(c).copy(isSelected = true)
        dao.deleteProfileAndReselect(stale)
        assertThat(dao.rows.getValue(a).isSelected).isTrue()
        assertThat(dao.rows).doesNotContainKey(c)
    }

    @Test
    fun `inserting a non-first profile with isSelected true does not get selected`() = runTest {
        val dao = FakeProfileDao()
        val first = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "a"))
        dao.setSelectedProfile(first)
        val second = dao.insertProfileAndSelectIfFirst(ProfileEntity(name = "b", isSelected = true))
        assertThat(dao.rows.getValue(second).isSelected).isFalse()
        assertThat(dao.rows.getValue(first).isSelected).isTrue()
    }

    @Test
    fun `replacement keeps the active node selected when it still exists`() = runTest {
        val dao = FakeProfileDao()
        val oldId = dao.insertProfile(
            ProfileEntity(name = "node-b", subscriptionId = 1L, createdAt = 1)
        )
        dao.setSelectedProfile(oldId)
        // Same socksHost on both entries: a matcher that compares socksHost
        // would pick the first feed entry instead of the active node.
        val nodeA = ProfileEntity(name = "node-a", socksHost = "9.9.9.9")
        val nodeB = ProfileEntity(name = "node-b", socksHost = "9.9.9.9")
        val replaced = dao.replaceProfilesForSubscription(1L, listOf(nodeA, nodeB))

        assertThat(replaced).isTrue()
        val selected = dao.rows.values.single { it.isSelected }
        assertThat(selected.name).isEqualTo("node-b")
        assertThat(selected.subscriptionId).isEqualTo(1L)
    }

    @Test
    fun `replacement falls back to the first feed entry when the active node is gone`() = runTest {
        val dao = FakeProfileDao()
        val oldId = dao.insertProfile(
            ProfileEntity(name = "old-node", subscriptionId = 1L, createdAt = 5)
        )
        dao.setSelectedProfile(oldId)
        // Newest row overall, in another subscription: a getNewestProfile()
        // fallback would select this decoy instead of the feed's first entry.
        dao.insertProfile(
            ProfileEntity(name = "decoy", subscriptionId = 2L, createdAt = Long.MAX_VALUE)
        )
        val feed = listOf(
            ProfileEntity(name = "node-a", createdAt = 0),
            ProfileEntity(name = "node-b", createdAt = 0)
        )
        val replaced = dao.replaceProfilesForSubscription(1L, feed)

        assertThat(replaced).isTrue()
        val selected = dao.rows.values.single { it.isSelected }
        assertThat(selected.name).isEqualTo("node-a")
        assertThat(selected.subscriptionId).isEqualTo(1L)
    }

    @Test
    fun `replacement is a no-op when the subscription was deleted mid-flight`() = runTest {
        val dao = FakeProfileDao()
        dao.insertProfile(ProfileEntity(name = "existing", subscriptionId = 1L, createdAt = 1))
        val before = LinkedHashMap(dao.rows)
        dao.subscriptionCount = 0

        val replaced = dao.replaceProfilesForSubscription(
            1L,
            listOf(ProfileEntity(name = "new-node"))
        )

        assertThat(replaced).isFalse()
        assertThat(dao.rows).isEqualTo(before)
    }

    @Test
    fun `deleting a subscription re-selects the newest remaining profile`() = runTest {
        val dao = FakeProfileDao()
        val sub1 = dao.insertProfile(
            ProfileEntity(name = "a", subscriptionId = 1L, createdAt = 1)
        )
        val sub2 = dao.insertProfile(
            ProfileEntity(name = "b", subscriptionId = 2L, createdAt = 2)
        )
        dao.setSelectedProfile(sub1)

        dao.deleteSubscriptionCascade(1L)

        assertThat(dao.rows).doesNotContainKey(sub1)
        assertThat(dao.rows.getValue(sub2).isSelected).isTrue()
    }

    @Test
    fun `deleting a non-selected subscription leaves the selection untouched`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfile(ProfileEntity(name = "a", subscriptionId = 1L, createdAt = 1))
        val b = dao.insertProfile(ProfileEntity(name = "b", subscriptionId = 2L, createdAt = 2))
        dao.setSelectedProfile(a)

        dao.deleteSubscriptionCascade(2L)

        assertThat(dao.rows).doesNotContainKey(b)
        assertThat(dao.rows.getValue(a).isSelected).isTrue()
    }

    @Test
    fun `deleting the last subscription leaves the table empty without crash`() = runTest {
        val dao = FakeProfileDao()
        val a = dao.insertProfile(ProfileEntity(name = "a", subscriptionId = 1L, createdAt = 1))
        dao.setSelectedProfile(a)

        dao.deleteSubscriptionCascade(1L)

        assertThat(dao.rows).isEmpty()
    }
}

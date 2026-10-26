package com.gooserelay.gooserelayvpn.ui.logs

import com.gooserelay.gooserelayvpn.util.VpnManager
import org.junit.Assert.assertEquals
import org.junit.Test

class LogsGroupingTest {

    private fun core(seq: Long, line: String) = VpnManager.LogEntry(
        seq = seq,
        line = line,
        source = VpnManager.LogSource.CORE
    )

    @Test
    fun blockKeysStayStableWhenOldEntriesAreEvicted() {
        val entries = (0L until 10L).map { seq ->
            core(seq, "2026-10-20 10:00:00 [INFO] line $seq")
        }

        val before = buildUiLogItems(entries.take(6)).associateBy { it.key }
        val after = buildUiLogItems(entries.drop(2)).associateBy { it.key }

        assertEquals(setOf("2", "3", "4", "5", "6", "7", "8", "9"), after.keys)
        (2L..5L).forEach { seq ->
            assertEquals(before.getValue(seq.toString()), after.getValue(seq.toString()))
        }
    }

    @Test
    fun continuationLinesGrowDetailsWithoutChangingTheBlockKey() {
        val head = core(21L, "2026-10-20 10:00:21 [INFO] head")

        val before = buildUiLogItems(listOf(head)).single()
        val after = buildUiLogItems(
            listOf(
                head,
                core(22L, "detail a"),
                core(23L, "detail b")
            )
        ).single()

        assertEquals("21", before.key)
        assertEquals("21", after.key)
        assertEquals(emptyList<String>(), before.details)
        assertEquals(listOf("detail a", "detail b"), after.details)
    }

    @Test
    fun identicalLinesStillGetDistinctBlockKeys() {
        val items = buildUiLogItems(
            listOf(
                core(31L, "2026-10-20 10:00:31 [INFO] same text"),
                core(32L, "2026-10-20 10:00:31 [INFO] same text")
            )
        )

        assertEquals(listOf("31", "32"), items.map { it.key })
    }
}

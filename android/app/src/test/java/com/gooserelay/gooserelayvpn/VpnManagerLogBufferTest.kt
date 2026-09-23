package com.gooserelay.gooserelayvpn

import com.google.common.truth.Truth.assertThat
import com.gooserelay.gooserelayvpn.util.VpnManager
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

class VpnManagerLogBufferTest {

    @Before
    fun setUp() {
        VpnManager.clearLogs()
    }

    @After
    fun tearDown() {
        VpnManager.clearLogs()
    }

    @Test
    fun `appendMoreThanMaxLines_keepsLast2000`() {
        repeat(2050) { i ->
            VpnManager.appendLog("buffer line ${i + 1}")
        }
        val entries = VpnManager.logEntries.value
        assertThat(entries).hasSize(2000)
        assertThat(entries.first().line).contains("buffer line 51")
        assertThat(entries.last().line).contains("buffer line 2050")
    }

    @Test
    fun `clearLogs_emptiesBuffer`() {
        VpnManager.appendLog("some line")
        assertThat(VpnManager.logEntries.value).isNotEmpty()
        VpnManager.clearLogs()
        assertThat(VpnManager.logEntries.value).isEmpty()
    }

    @Test
    fun `untimestampedLine_getsStamp`() {
        VpnManager.appendLog("plain log line without stamp")
        val entries = VpnManager.logEntries.value
        assertThat(entries).hasSize(1)
        assertThat(entries.single().line).matches("\\d{4}/.*")
    }

    @Test
    fun `concurrentAppends_doNotLoseLines`() {
        val threadCount = 8
        val perThread = 250
        val latch = CountDownLatch(threadCount)
        val failure = AtomicReference<Throwable>()
        repeat(threadCount) { t ->
            Thread {
                try {
                    repeat(perThread) { i ->
                        VpnManager.appendLog("concurrent t$t line $i")
                    }
                } catch (e: Throwable) {
                    failure.compareAndSet(null, e)
                } finally {
                    latch.countDown()
                }
            }.start()
        }
        val finished = latch.await(60, TimeUnit.SECONDS)
        assertThat(finished).isTrue()
        assertThat(failure.get()).isNull()
        assertThat(VpnManager.logEntries.value).hasSize(2000)
    }
}

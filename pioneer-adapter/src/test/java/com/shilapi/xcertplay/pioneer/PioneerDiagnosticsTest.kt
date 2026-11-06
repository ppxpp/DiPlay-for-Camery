package com.shilapi.xcertplay.pioneer

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.After
import org.junit.Assert.*
import org.junit.Test

class PioneerDiagnosticsTest {
    @Before fun enableForTest() { PioneerDiagnostics.enabled = true; PioneerDiagnostics.clear() }
    @After fun tearDown() {
        PioneerWiredSession.current?.close()
        PioneerDiagnostics.enabled = false
        PioneerDiagnostics.clear()
    }

    @Test fun disabledDiagnosticsAdmitNoTimingOrQueueSamples() {
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.WIRELESS, true)
        PioneerDiagnostics.enabled = false
        PioneerDiagnostics.record(session.id, "audio", 1_000)
        PioneerDiagnostics.queue(session.id, "audio", 1, 10, 0, 1)
        PioneerDiagnostics.slow(session.id, "slow")
        assertTrue(PioneerDiagnostics.drain().isEmpty())
        assertTrue(PioneerDiagnostics.queues().isEmpty())
        assertTrue(PioneerDiagnostics.drainSlow().isEmpty())
    }

    @Test fun existingMetricsDoNotWaitForTheDiagnosticsMonitor() {
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.WIRELESS, true)
        PioneerDiagnostics.enabled = true
        PioneerDiagnostics.record(session.id, "audio", 1_000)
        PioneerDiagnostics.record(session.id, "video", 1_000)
        val done = CountDownLatch(1)
        val writer = Thread {
            PioneerDiagnostics.record(session.id, "audio", 2_000)
            PioneerDiagnostics.record(session.id, "video", 2_000)
            done.countDown()
        }
        val completed = synchronized(PioneerDiagnostics) {
            writer.start()
            done.await(2, TimeUnit.SECONDS)
        }
        writer.join(2_000)
        assertTrue("Media recording waited for the diagnostics monitor", completed)
        assertTrue(PioneerDiagnostics.drain().all { "n=2 " in it })
    }

    @Test fun aBlockedMetricDrainDoesNotBlockOtherMediaMetrics() {
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.WIRELESS, true)
        PioneerDiagnostics.enabled = true
        PioneerDiagnostics.record(session.id, "audio", 1_000)
        PioneerDiagnostics.record(session.id, "video", 1_000)
        val field = PioneerDiagnostics.javaClass.getDeclaredField("metrics").apply { isAccessible = true }
        val generation = field.get(PioneerDiagnostics)
        val values = generation.javaClass.getDeclaredField("values").apply { isAccessible = true }
            .get(generation) as Map<*, *>
        val metric = values["audio"]!!
        val drained = CountDownLatch(1)
        val written = CountDownLatch(1)
        val drainer = Thread { PioneerDiagnostics.drain(); drained.countDown() }
        val writer = Thread { PioneerDiagnostics.record(session.id, "video", 2_000); written.countDown() }
        val completed = synchronized(metric) {
            drainer.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
            while (drainer.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, drainer.state)
            writer.start()
            written.await(2, TimeUnit.SECONDS)
        }
        drainer.join(2_000)
        writer.join(2_000)
        assertTrue("Other metrics waited for a blocked drain", completed)
        assertEquals(0L, drained.count)
    }

    @Test fun metricsRemainBoundedAndOldSessionsCannotPopulateNewGeneration() {
        PioneerDiagnostics.enabled = true
        val old = PioneerWiredSession.begin(PioneerWiredSession.Backend.WIRELESS, true)
        repeat(100) { PioneerDiagnostics.record(old.id, "metric$it", 1_000) }
        assertEquals(64, PioneerDiagnostics.drain().size)
        old.close()
        val next = PioneerWiredSession.begin(PioneerWiredSession.Backend.WIRELESS, true)
        PioneerDiagnostics.record(old.id, "stale", 1_000)
        PioneerDiagnostics.record(next.id, "fresh", 1_000)
        val result = PioneerDiagnostics.drain()
        assertEquals(1, result.size)
        assertTrue(result.single().startsWith("fresh n=1 "))
        assertTrue(PioneerDiagnostics.drain().single().startsWith("fresh n=0 "))
    }

    @Test fun concurrentTimingDrainPreservesEverySample() {
        PioneerDiagnostics.enabled = true
        val timing = PioneerTiming("test")
        val writer = Thread { repeat(20_000) { timing.record(1_000, true) } }
        val count = Regex(" n=(\\d+) ")
        val errors = Regex(" errors=(\\d+)")
        var total = 0L
        var failed = 0L
        fun drain() {
            val result = timing.drain()
            total += count.find(result)!!.groupValues[1].toLong()
            failed += errors.find(result)!!.groupValues[1].toLong()
        }
        writer.start()
        while (writer.isAlive) drain()
        writer.join()
        drain()
        assertEquals(20_000L, total)
        assertEquals(total, failed)
    }
}

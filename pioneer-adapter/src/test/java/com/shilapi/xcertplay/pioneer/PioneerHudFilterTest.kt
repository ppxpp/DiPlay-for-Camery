package com.shilapi.xcertplay.pioneer

import android.os.Looper
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSystemClock

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
@LooperMode(LooperMode.Mode.PAUSED)
class PioneerHudFilterTest {
    private fun awaitIdle(filter: PioneerHudFilter) {
        val busy = PioneerHudFilter::class.java.getDeclaredField("inFlight").apply { isAccessible = true }
            .get(filter) as AtomicBoolean
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(2)
        while (busy.get() && System.nanoTime() < until) Thread.yield()
        assertFalse("HUD control request did not finish", busy.get())
    }

    @Test fun inactiveMaintenanceAndFrameSnapshotsNeverStartRoot() {
        var requests = 0
        val filter = PioneerHudFilter(RuntimeEnvironment.getApplication()) { _, _, _ ->
            requests++; JSONObject()
        }
        repeat(1000) { filter.maintain(false); assertFalse(filter.ready()); filter.snapshot() }
        filter.release()
        assertEquals(0, requests)
    }

    @Test fun aStalledUiCannotRenewAndOnlyNavigationMaintenanceIssuesHeartbeats() {
        val calls = Collections.synchronizedList(mutableListOf<String>())
        var deadline = 0L
        val filter = PioneerHudFilter(RuntimeEnvironment.getApplication()) { op, payload, _ ->
            calls.add(op)
            if (op == "HUD_LEASE") deadline = payload.getLong("issuedMs") + 20000
            JSONObject().put("state", "RUNNING").put("ready", deadline > 0)
                .put("expiresAtElapsedMs", deadline).put("readyUntilElapsedMs", deadline).put("error", JSONObject.NULL)
        }
        filter.maintain(true); awaitIdle(filter)
        shadowOf(Looper.getMainLooper()).idle()
        filter.maintain(true); awaitIdle(filter)
        assertTrue(filter.ready())
        assertEquals(1, calls.count { it == "HUD_LEASE" })
        val before = calls.size
        repeat(1000) { assertTrue(filter.ready()); filter.snapshot() }
        assertEquals(before, calls.size)
        ShadowSystemClock.advanceBy(Duration.ofMillis(5000))
        filter.maintain(true); awaitIdle(filter) // Posts a probe; leave the UI queue stalled.
        repeat(1000) { filter.maintain(true) }
        assertEquals(1, calls.count { it == "HUD_LEASE" })
        ShadowSystemClock.advanceBy(Duration.ofMillis(15000))
        assertFalse(filter.ready())
        assertEquals(1, calls.count { it == "HUD_LEASE" })
        shadowOf(Looper.getMainLooper()).idle()
        filter.maintain(true); awaitIdle(filter)
        assertEquals(2, calls.count { it == "HUD_LEASE" })
        filter.release(); awaitIdle(filter)
    }

    @Test fun releaseDuringColdStartIsNonblockingAndIsNotLostWithoutAnotherWorkerCall() {
        val entered = CountDownLatch(1)
        val proceed = CountDownLatch(1)
        val released = CountDownLatch(1)
        val calls = Collections.synchronizedList(mutableListOf<String>())
        val filter = PioneerHudFilter(RuntimeEnvironment.getApplication()) { op, _, current ->
            calls.add(op)
            if (op == "HUD_START") {
                entered.countDown()
                assertTrue(proceed.await(2, TimeUnit.SECONDS))
                assertTrue(current())
            }
            if (op == "HUD_RELEASE") released.countDown()
            JSONObject().put("ready", false)
        }
        filter.maintain(true)
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        filter.release() // Must return while START is still held by the fake root.
        assertFalse(filter.ready())
        proceed.countDown()
        assertTrue(released.await(2, TimeUnit.SECONDS))
        awaitIdle(filter)
        assertFalse(calls.contains("HUD_LEASE"))
    }
}

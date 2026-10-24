package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.util.ArrayDeque
import org.junit.Assert.*
import org.junit.Test

class BoundedTouchDispatcherTest {
    private class Fixture(capacity: Int = 64) {
        val tasks = ArrayDeque<Runnable>()
        var session: Any = Any(); var token: Any = Any()
        var accepts = true; var rejectExecution = false
        val sent = mutableListOf<List<AirPlayContact>>()
        val failures = mutableListOf<String>()
        val queue = BoundedTouchDispatcher<Any>(
            execute = { if (rejectExecution) throw IllegalStateException("closed"); tasks.addLast(it) },
            isCurrent = { s, t -> s === session && t === token },
            send = { _, report -> sent += report; accepts },
            onFailure = { _, _, reason -> failures += reason }, capacity = capacity,
        )
        fun offer(x: Double, down: Boolean = true, move: Boolean = false, id: Int = 1) =
            queue.offer(session, token, listOf(AirPlayContact(id, x, 0.5, down)), move)
        fun drain() { while (tasks.isNotEmpty()) tasks.removeFirst().run() }
    }
    @Test fun onlyOneDrainIsScheduledAndLatestMoveRetainsDownUpBoundaries() {
        val f = Fixture(); f.offer(0.0); f.offer(0.1, move = true); f.offer(0.2, move = true)
        f.offer(0.3, down = false, move = true)
        assertEquals(1, f.tasks.size); f.drain()
        assertEquals(listOf(0.0, 0.2, 0.3), f.sent.map { it.single().x })
        assertFalse(f.sent.last().single().down)
    }
    @Test fun differentTouchShapeAndAnotherGestureCannotMerge() {
        val f = Fixture(); f.offer(0.0); f.offer(0.1, move = true); f.offer(0.2, move = true, id = 2)
        f.offer(0.3, down = false); f.offer(0.4); f.offer(0.5, move = true); f.drain()
        assertEquals(6, f.sent.size)
    }
    @Test fun overflowingTransitionsFailInsteadOfSilentlyLosingAnUp() {
        val f = Fixture(capacity = 2); f.offer(0.0); f.offer(0.1)
        assertFalse(f.offer(0.2, down = false)); assertEquals(listOf("QUEUE_FULL"), f.failures)
        assertFalse(f.offer(0.3)); f.drain(); assertTrue(f.sent.isEmpty())
    }
    @Test fun replacedScreenTokenPurgesOldReportsBeforeSendingNewGesture() {
        val f = Fixture(); f.offer(0.0); f.token = Any(); f.offer(0.1); f.drain()
        assertEquals(listOf(0.1), f.sent.map { it.single().x })
    }
    @Test fun staleReportIsRecheckedWhenItActuallyRuns() {
        val f = Fixture(); f.offer(0.0); f.session = Any(); f.drain()
        assertTrue(f.sent.isEmpty()); assertTrue(f.failures.isEmpty())
    }
    @Test fun sendFailureStopsQueuedReportsForThatScreen() {
        val f = Fixture(); f.offer(0.0); f.offer(0.1); f.accepts = false; f.drain()
        assertEquals(1, f.sent.size); assertEquals(listOf("SEND_FAILED"), f.failures)
        assertFalse(f.offer(0.2))
    }
    @Test fun rejectedExecutorAndCloseDoNotLeaveReportsRunnable() {
        val f = Fixture(); f.rejectExecution = true
        assertFalse(f.offer(0.0)); assertEquals(listOf("EXECUTOR_REJECTED"), f.failures)
        val other = Fixture(); other.offer(0.0); other.queue.close(); other.drain()
        assertTrue(other.sent.isEmpty()); assertFalse(other.offer(0.1))
    }
}

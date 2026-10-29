package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import org.junit.Assert.*
import org.junit.Test

class HotspotRetentionSlotTest {
    private var time=0L
    private val tasks=mutableListOf<() -> Unit>()
    private val closed=mutableListOf<String>()
    private val slot=HotspotRetentionSlot<String>({time},{ _, task -> tasks+=task },{closed+=it})
    @Test fun zeroClosesImmediately() {
        slot.retain("ap",0)
        assertEquals(listOf("ap"),closed)
        assertNull(slot.take())
    }
    @Test fun reconnectCancelsOldExpiryEvenAfterAnotherStop() {
        slot.retain("ap",30)
        val old=tasks.first()
        time=20000
        assertEquals("ap",slot.take())
        slot.retain("ap",30)
        time=30000;old()
        assertTrue(closed.isEmpty())
        time=50000;tasks.last()()
        assertEquals(listOf("ap"),closed)
    }
    @Test fun expiredLeaseCannotBeReclaimedBeforeTimerRuns() {
        slot.retain("ap",30)
        time=30000
        assertNull(slot.take())
        assertEquals(listOf("ap"),closed)
        tasks.first()()
        assertEquals(1,closed.size)
    }
    @Test fun changingDelayUsesOriginalStopTime() {
        slot.retain("ap",30)
        time=15000;slot.reschedule(10)
        assertEquals(listOf("ap"),closed)
    }
    @Test fun extendingDelayDoesNotLetOldTimerCloseLease() {
        slot.retain("ap",30)
        val old=tasks.first()
        time=15000;slot.reschedule(60)
        time=30000;old()
        assertTrue(closed.isEmpty())
        time=60000;tasks.last()()
        assertEquals(listOf("ap"),closed)
    }
    @Test fun queuedCleanupCannotCloseLaterIdleLease() {
        slot.retain("first",30)
        val clear=slot.clearAction()
        assertEquals("first",slot.take())
        slot.retain("second",30)
        clear()
        assertTrue(closed.isEmpty())
        assertEquals("second",slot.take())
    }
}

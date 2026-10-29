package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class PioneerVendorCallsTest {
    @Test fun timedOutCallBlocksNewIpcUntilItReallyFinishesThenAllowsCleanup() {
        val calls = PioneerVendorCalls(50)
        val release = CountDownLatch(1)
        try {
            assertThrows(IllegalStateException::class.java) {
                calls.call("connect") { release.await(); true }
            }
            assertThrows(IllegalStateException::class.java) {
                calls.call("cleanup") { error("Must not queue while original IPC is live") }
            }
        } finally { release.countDown() }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (true) {
            val result = runCatching { calls.call("cleanup") { "released" } }
            if (result.isSuccess) { assertEquals("released", result.getOrThrow()); break }
            if (System.nanoTime() >= deadline) throw result.exceptionOrNull()!!
            Thread.sleep(10)
        }
    }

    @Test fun completedFailureDoesNotPermanentlyPoisonCleanup() {
        val calls = PioneerVendorCalls()
        val original = IllegalArgumentException("rejected")
        assertSame(original, assertThrows(IllegalArgumentException::class.java) { calls.call("connect") { throw original } })
        assertTrue(calls.call("cleanup") { true })
    }
}

package com.shilapi.xcertplay.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class NativeNcmPreparationGateTest {
    @Test fun homepageSchedulesOnceAndOnlyWhenEligible() {
        val gate = NativeNcmPreparationGate()
        var called = 0
        val direct: (Runnable) -> Unit = { it.run() }
        gate.schedule({ false }, direct) { called++ }
        assertEquals(0, called)
        repeat(3) { gate.schedule({ true }, direct) { called++ } }
        assertEquals(1, called)
    }

    @Test fun userspaceConnectionDoesNotSuppressLaterManualNativeSelection() {
        val gate = NativeNcmPreparationGate()
        gate.awaitReady({ true }, 100)
        var called = false
        gate.schedule({ true }, { it.run() }) { called = true }
        assertTrue(called)
    }

    @Test fun connectionBeforeWorkerStartsSkipsPrewarm() {
        val gate = NativeNcmPreparationGate()
        var task: Runnable? = null
        var called = false
        gate.schedule({ true }, { task = it }) { called = true }
        gate.connectionRequested()
        task!!.run()
        gate.awaitReady({ true }, 100)
        gate.schedule({ true }, { it.run() }) { called = true }
        assertFalse(called)
    }

    @Test fun connectionWaitsForRunningPreparationWithoutLoadingTwice() {
        val gate = NativeNcmPreparationGate()
        val entered = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            gate.schedule({ true }, { pool.execute(it) }) {
                entered.countDown()
                assertTrue(finish.await(2, TimeUnit.SECONDS))
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            gate.connectionRequested()
            val waiting = pool.submit { gate.awaitReady({ true }, 2000) }
            assertThrows(IOException::class.java) { gate.awaitReady({ true }, 10) }
            assertFalse(waiting.isDone)
            finish.countDown()
            waiting.get(2, TimeUnit.SECONDS)
        } finally { finish.countDown(); pool.shutdownNow() }
    }

    @Test fun preferenceChangeBeforeWorkerRunsAndCancelledConnectionDoNotMutate() {
        val gate = NativeNcmPreparationGate()
        var eligible = true
        var task: Runnable? = null
        var called = false
        gate.schedule({ eligible }, { task = it }) { called = true }
        eligible = false
        task!!.run()
        assertFalse(called)
        assertThrows(IOException::class.java) { gate.awaitReady({ false }, 100) }
    }

    @Test fun failedPrewarmReleasesGateForFormalRetry() {
        val gate = NativeNcmPreparationGate()
        assertThrows(IllegalStateException::class.java) {
            gate.schedule({ true }, { it.run() }) { error("prewarm rejected") }
        }
        gate.connectionRequested()
        gate.awaitReady({ true }, 100)
    }
}

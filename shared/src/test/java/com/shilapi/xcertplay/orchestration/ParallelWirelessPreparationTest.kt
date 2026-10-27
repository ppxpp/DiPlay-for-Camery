package com.shilapi.xcertplay.orchestration

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.*
import org.junit.Test

class ParallelWirelessPreparationTest {
    @Test fun callerCanPrepareNetworkWhileBluetoothIsBlocked() {
        val entered = CountDownLatch(1)
        val networkReady = CountDownLatch(1)
        val preparation = ParallelWirelessPreparation("test-auth") {
            entered.countDown()
            check(networkReady.await(2, TimeUnit.SECONDS))
            "authenticated"
        }
        try {
            preparation.start()
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            networkReady.countDown()
            assertEquals("authenticated", preparation.await { false })
        } finally { preparation.close() }
    }

    @Test fun closingInterruptsAndJoinsTheOldWorker() {
        val entered = CountDownLatch(1)
        val exited = AtomicBoolean(false)
        val preparation = ParallelWirelessPreparation("test-cancel") {
            try { entered.countDown(); CountDownLatch(1).await() }
            finally { exited.set(true) }
        }
        preparation.start()
        assertTrue(entered.await(2, TimeUnit.SECONDS))
        preparation.close()
        assertTrue(exited.get())
    }

    @Test fun cancelledBeforeStartCannotPublishALateResult() {
        val executed = AtomicBoolean(false)
        val preparation = ParallelWirelessPreparation("test-late") { executed.set(true) }
        preparation.cancel()
        preparation.start()
        preparation.close()
        assertFalse(executed.get())
    }

    @Test fun originalFailureIsPreserved() {
        val failure = IOException("Bluetooth failed")
        val preparation = ParallelWirelessPreparation<Unit>("test-failure") { throw failure }
        try {
            preparation.start()
            assertSame(failure, assertThrows(IOException::class.java) { preparation.await { false } })
            assertSame(failure, preparation.failureOrNull())
        } finally { preparation.close() }
    }
}

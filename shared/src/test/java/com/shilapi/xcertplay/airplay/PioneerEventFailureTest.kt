package com.shilapi.xcertplay.airplay

import com.shilapi.xcertplay.pioneer.PioneerWiredSession
import java.io.IOException
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerEventFailureTest {
    @After fun cleanup() { PioneerWiredSession.current?.close() }

    @Test fun writeFailureIsReportedForBothBackendsEvenWhenCloseInvalidatesTheSession() {
        for (backend in PioneerWiredSession.Backend.entries) {
            PioneerWiredSession.begin(backend, true).use {
                assertFailure(blockWrite = false)
            }
        }
    }

    @Test fun stalledWriteReportsTransportFailureAndUnblocksBothBackends() {
        for (backend in PioneerWiredSession.Backend.entries) {
            PioneerWiredSession.begin(backend, true).use {
                assertFailure(blockWrite = true)
            }
        }
    }

    private fun assertFailure(blockWrite: Boolean) {
        val reported = CountDownLatch(1)
        val count = AtomicInteger()
        val event = FailingSocket(blockWrite)
        val session = AirPlaySession(Socket(),
            AirPlayConfig(deviceName = "test", deviceId = "02:00:00:00:00:01", btMac = "02:00:00:00:00:02",
                sourceVersion = "1", main = AirPlayDisplayConfig(800, 480)),
            AirPlayIdentity(ByteArray(32), ByteArray(32), "test"), PairingStore(), null,
            object : AirPlaySessionListener {
                override fun onTransportError(message: String) {
                    count.incrementAndGet()
                    reported.countDown()
                }
            }, object : AirPlayMediaHandler {})
        ReflectionHelpers.setField(session, "eventSocket", event)
        ReflectionHelpers.setField(session, "eventCipher", ControlCipher(ByteArray(32), ByteArray(32)))
        try {
            assertFalse(session.sendCommand(mapOf("type" to "setNightMode", "params" to mapOf("nightMode" to true))))
            assertTrue("Failure must reach the service, not only close the session", reported.await(5, TimeUnit.SECONDS))
            assertTrue(session.isClosed)
            assertEquals(1, count.get())
            assertTrue(event.isClosed)
        } finally { session.close(); event.close() }
    }

    private class FailingSocket(private val block: Boolean) : Socket() {
        private val closed = AtomicBoolean()
        private val released = CountDownLatch(1)
        override fun isClosed(): Boolean = closed.get()
        override fun close() { closed.set(true); released.countDown() }
        override fun getOutputStream(): OutputStream = object : OutputStream() {
            override fun write(value: Int) {
                if (block && !released.await(8, TimeUnit.SECONDS)) throw IOException("Watchdog did not unblock writer")
                throw IOException("Injected event write failure")
            }
        }
    }
}

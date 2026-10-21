package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.TimeoutException
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.RealObject
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE,
    shadows = [PipelineRequestShadow::class, PipelineConnectionShadow::class])
class NcmReadPipelineTest {
    @Before fun reset() = PipelineReplay.reset()

    @Test fun anotherRequestStaysSubmittedAndOnlyTwoBuffersAreReusedInOrder() {
        val ncm = bridge()
        val ids = mutableListOf<Int>()
        try {
            repeat(12) {
                assertEquals(4, ncm.recvDirectChunk(100) { buffer, length ->
                    assertEquals(4, length)
                    // The consumed buffer is not owned by any queued request.
                    assertEquals(1, PipelineReplay.pending.size)
                    assertFalse(PipelineReplay.pending.any { it.buffer === buffer })
                    ids.add(buffer.int)
                    1
                })
            }
            assertEquals((1..12).toList(), ids)
            assertEquals(2, PipelineReplay.buffers.size)
            assertEquals(2, PipelineReplay.pending.size)
        } finally { ncm.close() }
        assertEquals(2, PipelineReplay.cancelled.size)
        assertEquals(2, PipelineReplay.closed.size)
    }

    @Test fun timeoutRetainsBothRequestsWithoutClearingOrRequeueingTheirBuffers() {
        val ncm = bridge()
        try {
            PipelineReplay.timeout = true
            assertNull(ncm.recvDirectChunk(1) { _, _ -> fail("No completion expected"); 0 })
            assertEquals(2, PipelineReplay.submissions)
            PipelineReplay.timeout = false
            assertEquals(4, ncm.recvDirectChunk(100) { buffer, _ -> assertEquals(1, buffer.int); 1 })
            assertEquals(3, PipelineReplay.submissions)
        } finally { ncm.close() }
    }

    @Test fun fourReceiveSlotsKeepThreeRequestsInFlightWhileOneIsConsumed() {
        val ncm = bridge()
        try {
            repeat(8) {
                assertEquals(4, ncm.recvDirectChunk(100, readSlots = 4) { buffer, length ->
                    assertEquals(4, length)
                    assertEquals(3, PipelineReplay.pending.size)
                    assertFalse(PipelineReplay.pending.any { it.buffer === buffer })
                    1
                })
            }
            assertEquals(4, PipelineReplay.buffers.size)
            assertEquals(4, PipelineReplay.pending.size)
        } finally { ncm.close() }
    }

    @Test fun parserFailureDoesNotReuseBorrowedBufferAndCloseCancelsTheOtherRequest() {
        val ncm = bridge()
        try {
            try { ncm.recvDirectChunk(100) { _, _ -> -1 }; fail("Expected parser failure") }
            catch (_: IphoneUsbException.DeviceUnavailable) { }
            assertEquals(2, PipelineReplay.submissions)
        } finally { ncm.close() }
        assertEquals(2, PipelineReplay.closed.size)
    }

    @Test fun secondQueueRejectionStillOwnsAndClosesBothPublishedRequests() {
        val ncm = bridge()
        PipelineReplay.rejectFrom = 2
        try {
            try { ncm.recvDirectChunk(100) { _, _ -> 1 }; fail("Expected rejection") }
            catch (_: IphoneUsbException.DeviceUnavailable) { }
        } finally { ncm.close() }
        assertEquals(2, PipelineReplay.closed.size)
        assertEquals(2, PipelineReplay.cancelled.size)
    }

    @Test fun drainedRequestsAreResubmittedAfterInterfaceRebuild() {
        val ncm = bridge()
        try {
            ncm.recvDirectChunk(100) { _, _ -> 1 }
            val drain = NcmUsbBridge::class.java.getDeclaredMethod("cancelReadsForRebuild").apply { isAccessible = true }
            assertEquals(true, drain.invoke(ncm))
            assertTrue(PipelineReplay.pending.isEmpty())
            val previous = PipelineReplay.submissions
            ncm.recvDirectChunk(100) { buffer, _ -> assertEquals(2, buffer.int); 1 }
            assertEquals(previous + 3, PipelineReplay.submissions) // 2 resubmits + consumed slot
            assertEquals(2, PipelineReplay.pending.size)
        } finally { ncm.close() }
    }

    @Test fun receiveModeCannotChangeAfterPipelineStarts() {
        val ncm = bridge()
        try {
            ncm.recvDirectChunk(100) { _, _ -> 1 }
            try { ncm.recv(1); fail("Expected fixed receive mode") }
            catch (_: IllegalStateException) { }
        } finally { ncm.close() }
    }

    private fun bridge(): NcmUsbBridge {
        val connection = ReflectionHelpers.callConstructor(UsbDeviceConnection::class.java,
            ClassParameter.from(UsbDevice::class.java, null))
        fun endpoint(address: Int): UsbEndpoint = ReflectionHelpers.callConstructor(UsbEndpoint::class.java,
            ClassParameter.from(Int::class.javaPrimitiveType, address), ClassParameter.from(Int::class.javaPrimitiveType, 2),
            ClassParameter.from(Int::class.javaPrimitiveType, 512), ClassParameter.from(Int::class.javaPrimitiveType, 0))
        return NcmUsbBridge(connection, endpoint(6), endpoint(0x85), null, emptyList(), null)
    }
}

object PipelineReplay {
    data class Pending(val request: UsbRequest, val buffer: ByteBuffer)
    val pending = ArrayDeque<Pending>()
    val buffers = mutableSetOf<Int>()
    val cancelled = mutableSetOf<UsbRequest>()
    val closed = mutableSetOf<UsbRequest>()
    var submissions = 0
    var sequence = 0
    var timeout = false
    var rejectFrom = Int.MAX_VALUE
    fun reset() { pending.clear(); buffers.clear(); cancelled.clear(); closed.clear()
        submissions = 0; sequence = 0; timeout = false; rejectFrom = Int.MAX_VALUE }
}
@Implements(UsbRequest::class)
class PipelineRequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation(minSdk = 26) fun queue(buffer: ByteBuffer): Boolean {
        PipelineReplay.submissions++
        if (PipelineReplay.submissions >= PipelineReplay.rejectFrom) return false
        PipelineReplay.buffers.add(System.identityHashCode(buffer))
        PipelineReplay.pending.addLast(PipelineReplay.Pending(request, buffer))
        return true
    }
    @Implementation fun cancel(): Boolean { PipelineReplay.cancelled.add(request); return true }
    @Implementation fun close() { PipelineReplay.closed.add(request) }
}
@Implements(UsbDeviceConnection::class)
class PipelineConnectionShadow {
    @Implementation(minSdk = 26) fun requestWait(timeout: Long): UsbRequest {
        if (PipelineReplay.timeout) throw TimeoutException()
        val next = PipelineReplay.pending.removeFirst()
        if (!PipelineReplay.cancelled.remove(next.request)) next.buffer.putInt(++PipelineReplay.sequence)
        return next.request
    }
    @Implementation fun close() = Unit
}

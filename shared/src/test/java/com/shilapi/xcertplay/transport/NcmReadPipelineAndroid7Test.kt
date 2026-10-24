package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbRequest
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
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

/** Android 7's reaper and the consumer run concurrently; completion permits model a slow phone. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE,
    shadows = [Pipeline7RequestShadow::class, Pipeline7ConnectionShadow::class])
class NcmReadPipelineAndroid7Test {
    @Before fun reset() = Pipeline7Replay.reset()

    @Test fun blockingReaperPreservesFifoAndBufferOwnershipAndStopsAfterCancellation() {
        val ncm = bridge()
        try {
            repeat(8) { index ->
                Pipeline7Replay.permits.release()
                assertEquals(4, ncm.recvDirectChunk(1_000) { buffer, _ ->
                    assertEquals(index + 1, buffer.int)
                    assertFalse(Pipeline7Replay.owned.values.any { it === buffer })
                    assertEquals(1, Pipeline7Replay.owned.size)
                    1
                })
            }
            assertEquals(2, Pipeline7Replay.buffers.size)
        } finally { ncm.close() }
        assertEquals(2, Pipeline7Replay.closed.size)
        assertTrue(Pipeline7Replay.owned.isEmpty())
    }

    @Test fun timeoutKeepsBothRequestsAndRebuildDrainsAndResubmitsBoth() {
        val ncm = bridge()
        try {
            assertNull(ncm.recvDirectChunk(2) { _, _ -> fail("Phone has not completed a read"); 0 })
            assertEquals(2, Pipeline7Replay.owned.size)
            val drain = NcmUsbBridge::class.java.getDeclaredMethod("cancelReadsForRebuild").apply { isAccessible = true }
            assertEquals(true, drain.invoke(ncm))
            assertTrue(Pipeline7Replay.owned.isEmpty())
            Pipeline7Replay.permits.release()
            assertEquals(4, ncm.recvDirectChunk(1_000) { buffer, _ -> assertEquals(1, buffer.int); 1 })
            assertEquals(2, Pipeline7Replay.owned.size)
        } finally { ncm.close() }
        assertEquals(2, Pipeline7Replay.closed.size)
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
object Pipeline7Replay {
    data class Submission(val request: UsbRequest, val buffer: ByteBuffer)
    val submissions = LinkedBlockingQueue<Submission>()
    val permits = Semaphore(0)
    val owned = ConcurrentHashMap<UsbRequest, ByteBuffer>()
    val cancelled = ConcurrentHashMap.newKeySet<UsbRequest>()
    val closed = ConcurrentHashMap.newKeySet<UsbRequest>()
    val buffers = ConcurrentHashMap.newKeySet<Int>()
    var sequence = 0 // only the single reaper writes this
    fun reset() { submissions.clear(); permits.drainPermits(); owned.clear(); cancelled.clear()
        closed.clear(); buffers.clear(); sequence = 0 }
}
@Implements(UsbRequest::class)
class Pipeline7RequestShadow {
    @RealObject lateinit var request: UsbRequest
    @Implementation fun initialize(connection: UsbDeviceConnection, endpoint: UsbEndpoint) = true
    @Implementation fun queue(buffer: ByteBuffer, length: Int): Boolean {
        check(buffer.position() == 0 && length == buffer.remaining())
        check(Pipeline7Replay.owned.putIfAbsent(request, buffer) == null)
        Pipeline7Replay.buffers.add(System.identityHashCode(buffer))
        Pipeline7Replay.submissions.put(Pipeline7Replay.Submission(request, buffer))
        return true
    }
    @Implementation fun cancel(): Boolean {
        if (Pipeline7Replay.owned.containsKey(request)) {
            Pipeline7Replay.cancelled.add(request)
            Pipeline7Replay.permits.release()
        }
        return true
    }
    @Implementation fun close() { Pipeline7Replay.closed.add(request) }
}
@Implements(UsbDeviceConnection::class)
class Pipeline7ConnectionShadow {
    @Implementation fun requestWait(): UsbRequest {
        val next = Pipeline7Replay.submissions.take()
        Pipeline7Replay.permits.acquire()
        if (!Pipeline7Replay.cancelled.remove(next.request)) next.buffer.putInt(++Pipeline7Replay.sequence)
        Pipeline7Replay.owned.remove(next.request)
        return next.request
    }
    @Implementation fun close() = Unit
}

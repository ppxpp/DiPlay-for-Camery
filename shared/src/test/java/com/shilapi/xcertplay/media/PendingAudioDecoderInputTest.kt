package com.shilapi.xcertplay.media

import java.util.concurrent.LinkedBlockingQueue
import org.junit.Assert.*
import org.junit.Test

class PendingAudioDecoderInputTest {
    @Test fun transientPressurePreservesPayloadAndPtsAndQueuesExactlyOnce() {
        val pending = PendingAudioDecoderInput(100)
        val payload = byteArrayOf(1, 7, 9)
        val pts = 4_294_967_295L
        pending.offer(payload, pts, 1000)
        var calls = 0
        val queueInput: (ByteArray, Long) -> Boolean = { bytes, timestamp ->
            assertSame(payload, bytes)
            assertEquals(pts, timestamp)
            ++calls == 3
        }
        assertEquals(PendingAudioDecoderInput.Result.RETRY, pending.attempt(1000, true, queueInput))
        assertEquals(PendingAudioDecoderInput.Result.RETRY, pending.attempt(1020, true, queueInput))
        assertEquals(PendingAudioDecoderInput.Result.ACCEPTED, pending.attempt(1040, true, queueInput))
        assertFalse(pending.hasPending)
        assertEquals(PendingAudioDecoderInput.Result.EMPTY, pending.attempt(1060, true, queueInput))
        assertEquals(3, calls)
    }

    @Test fun permanentPressureExpiresWithoutResettingDeadlineAndAllowsNextPacket() {
        val pending = PendingAudioDecoderInput(100)
        pending.offer(byteArrayOf(1), 11, 0)
        var calls = 0
        repeat(10) {
            assertEquals(PendingAudioDecoderInput.Result.RETRY,
                pending.attempt(it * 10L, true) { _, _ -> calls++; false })
        }
        assertEquals(PendingAudioDecoderInput.Result.EXPIRED,
            pending.attempt(100, true) { _, _ -> fail("Expired packet must not be submitted"); true })
        assertEquals(10, calls)
        assertFalse(pending.hasPending)
        pending.offer(byteArrayOf(2), 22, 101)
        assertEquals(PendingAudioDecoderInput.Result.ACCEPTED,
            pending.attempt(101, true) { _, pts -> assertEquals(22L, pts); true })
    }

    @Test fun cancellationClearsPendingWithoutTouchingDecoder() {
        val pending = PendingAudioDecoderInput(100)
        pending.offer(byteArrayOf(1), 0, 0)
        assertEquals(PendingAudioDecoderInput.Result.CANCELLED,
            pending.attempt(50, false) { _, _ -> fail("Cancelled decoder must not be touched"); true })
        assertFalse(pending.hasPending)
    }

    @Test fun freedSlotAfterDeadlineAcceptsOriginalPacketExactlyOnce() {
        val pending = PendingAudioDecoderInput(100)
        val payload = byteArrayOf(3, 5, 7)
        val ptsUs = 123_456L
        pending.offer(payload, ptsUs, 1000)
        var normalAttempts = 0
        val normalInput: (ByteArray, Long) -> Boolean = { _, _ -> normalAttempts++; false }
        assertEquals(PendingAudioDecoderInput.Result.RETRY, pending.attempt(1000, true, normalInput))
        // Output draining frees a slot, but its PCM write takes us beyond the deadline.
        var finalProbes = 0
        val finalProbe: (ByteArray, Long) -> Boolean = { bytes, pts ->
            finalProbes++
            assertSame(payload, bytes)
            assertEquals(ptsUs, pts)
            true
        }
        assertEquals(PendingAudioDecoderInput.Result.ACCEPTED,
            pending.attempt(1120, true, normalInput, finalProbe))
        assertFalse(pending.hasPending)
        assertEquals(PendingAudioDecoderInput.Result.EMPTY,
            pending.attempt(1130, true, normalInput, finalProbe))
        assertEquals(1, normalAttempts)
        assertEquals(1, finalProbes)
    }

    @Test fun beforeDeadlineUsesNormalInputWithoutCallingFinalProbe() {
        val pending = PendingAudioDecoderInput(100)
        pending.offer(byteArrayOf(1), 9, 0)
        var normalCalls = 0
        val normalInput: (ByteArray, Long) -> Boolean = { _, _ -> normalCalls++; normalCalls == 2 }
        val forbiddenProbe: (ByteArray, Long) -> Boolean = { _, _ -> fail("Final probe is only for expired input"); false }
        assertEquals(PendingAudioDecoderInput.Result.RETRY,
            pending.attempt(10, true, normalInput, forbiddenProbe))
        assertEquals(PendingAudioDecoderInput.Result.ACCEPTED,
            pending.attempt(20, true, normalInput, forbiddenProbe))
        assertEquals(2, normalCalls)
        assertFalse(pending.hasPending)
    }

    @Test fun finalProbeUnderPermanentPressureExpiresOnceAndAllowsNextPacket() {
        val pending = PendingAudioDecoderInput(100)
        pending.offer(byteArrayOf(1), 11, 0)
        var probes = 0
        val normalInput: (ByteArray, Long) -> Boolean = { _, _ -> fail("Expired input must not block"); false }
        val finalProbe: (ByteArray, Long) -> Boolean = { _, _ -> probes++; false }
        assertEquals(PendingAudioDecoderInput.Result.EXPIRED,
            pending.attempt(100, true, normalInput, finalProbe))
        assertEquals(PendingAudioDecoderInput.Result.EMPTY,
            pending.attempt(101, true, normalInput, finalProbe))
        assertEquals(1, probes)
        pending.offer(byteArrayOf(2), 22, 102)
        assertEquals(PendingAudioDecoderInput.Result.ACCEPTED,
            pending.attempt(102, true) { _, pts -> assertEquals(22L, pts); true })
    }

    @Test fun cancellationAfterDeadlineSkipsBothNormalInputAndFinalProbe() {
        val pending = PendingAudioDecoderInput(100)
        pending.offer(byteArrayOf(1), 0, 0)
        val forbidden: (ByteArray, Long) -> Boolean = { _, _ -> fail("Cancelled codec must not be touched"); false }
        assertEquals(PendingAudioDecoderInput.Result.CANCELLED,
            pending.attempt(120, false, forbidden, forbidden))
        assertFalse(pending.hasPending)
    }

    @Test fun cannotOverwriteUnacceptedPacket() {
        val pending = PendingAudioDecoderInput(100)
        val first = byteArrayOf(1)
        pending.offer(first, 1, 0)
        try {
            pending.offer(byteArrayOf(2), 2, 10)
            fail("A second packet must not overwrite the first")
        } catch (_: IllegalStateException) { }
        pending.attempt(20, true) { payload, pts ->
            assertSame(first, payload)
            assertEquals(1L, pts)
            true
        }
    }

    @Test fun stalledPendingDoesNotPreventIngressLagRecovery() {
        val pending = PendingAudioDecoderInput(100_000_000)
        val queue = LinkedBlockingQueue<Int>(192)
        val guard = MediaPacketLagGuard(48_000)
        pending.offer(byteArrayOf(1), 0, 1_000_000_000)
        repeat(150) { queue.offer(it * 1024) }
        assertEquals(0, guard.recover(queue, 149 * 1024, { it }, 1_000_000_000))
        assertTrue(guard.recover(queue, 149 * 1024, { it }, 2_500_000_000) > 0)
        assertEquals(PendingAudioDecoderInput.Result.EXPIRED,
            pending.attempt(2_500_000_000, true) { _, _ -> fail("Stale pending input"); true })
        assertTrue(queue.size < 25)
    }
}

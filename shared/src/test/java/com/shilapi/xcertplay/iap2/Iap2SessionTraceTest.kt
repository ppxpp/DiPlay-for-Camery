package com.shilapi.xcertplay.iap2

import com.shilapi.xcertplay.iap2.session.Iap2Session
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import com.shilapi.xcertplay.transport.Iap2CsmChannel
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class Iap2SessionTraceTest {
    @Test fun disabledFramesKeepReadyCloseAndFailureTracesForEveryFactory() {
        for (mode in Mode.entries) withSession(mode, { false }) { session, peer, traces ->
            exchange(session, peer)
            assertEquals(listOf("IAP2 READY [test] ready=true"), traces)
            session.close()
            assertEquals("IAP2 CLOSE [test]", traces.last())
            try {
                session.send(FRAME)
                fail("A closed session must reject sends")
            } catch (_: IOException) {
                assertTrue(traces.last().contains(" FAILED: "))
            }
            try {
                session.recv(0)
                fail("A closed session must reject receives")
            } catch (_: IOException) {
                assertTrue(traces.last().startsWith("IAP2 RX [test] FAILED: "))
            }
        }
    }

    @Test fun enabledAndDefaultFramesKeepDetailedTransmitAndReceiveTraces() {
        for (mode in Mode.entries) for (gate in listOf(null, { true })) {
            withSession(mode, gate) { session, peer, traces ->
                exchange(session, peer)
                assertEquals(3, traces.size)
                assertTrue(traces[1].startsWith("IAP2 TX [test]"))
                assertTrue(traces[1].contains("raw-body="))
                assertTrue(traces[2].startsWith("IAP2 RX [test]"))
                assertTrue(traces[2].contains("raw-body="))
            }
        }
    }

    @Test fun gateIsEvaluatedForEachFrameWithoutReopeningTheSession() {
        var enabled = false
        withSession(Mode.WRAPPED, { enabled }) { session, peer, traces ->
            exchange(session, peer)
            assertEquals(1, traces.size)
            enabled = true
            exchange(session, peer)
            assertEquals(3, traces.size)
            enabled = false
            exchange(session, peer)
            assertEquals(3, traces.size)
        }
    }

    private fun exchange(session: Iap2Session, peer: Iap2CsmChannel) {
        session.send(FRAME)
        assertEquals(FRAME, peer.recv(2_000))
        peer.send(FRAME)
        assertEquals(FRAME, session.recv(2_000))
    }

    private fun withSession(
        mode: Mode,
        gate: (() -> Boolean)?,
        check: (Iap2Session, Iap2CsmChannel, MutableList<String>) -> Unit,
    ) {
        val left = LinkedBlockingQueue<ByteArray>()
        val right = LinkedBlockingQueue<ByteArray>()
        val local = Pipe(left, right)
        val remote = Pipe(right, left)
        val traces = mutableListOf<String>()
        val onTrace: (String) -> Unit = { traces.add(it) }
        val peer = if (mode == Mode.WIRELESS) Iap2CsmChannel.open(remote)
            else Iap2CsmChannel.openWireless(remote)
        peer.use {
            val session = when (mode) {
                Mode.WIRED -> if (gate == null) Iap2Session.open(local, "test", onTrace)
                    else Iap2Session.open(local, "test", onTrace, traceFrames = gate)
                Mode.WIRELESS -> if (gate == null) Iap2Session.openWireless(local, "test", onTrace)
                    else Iap2Session.openWireless(local, "test", onTrace, traceFrames = gate)
                Mode.TUNNEL -> if (gate == null) Iap2Session.openTunnel(local, "test", onTrace)
                    else Iap2Session.openTunnel(local, "test", onTrace, traceFrames = gate)
                Mode.WRAPPED -> {
                    val channel = Iap2CsmChannel.open(local)
                    if (gate == null) Iap2Session.wrap(channel, "test", onTrace)
                    else Iap2Session.wrap(channel, "test", onTrace, traceFrames = gate)
                }
            }
            session.use {
                assertTrue("$mode local link was not ready", session.awaitReady(2_000))
                assertTrue("$mode peer link was not ready", peer.awaitReady(2_000))
                check(session, peer, traces)
            }
        }
    }

    private class Pipe(
        private val input: LinkedBlockingQueue<ByteArray>,
        private val output: LinkedBlockingQueue<ByteArray>,
    ) : BlockingDuplexByteStream {
        override fun send(data: ByteArray) { output.put(data.copyOf()) }
        override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
            input.poll(timeoutMillis, TimeUnit.MILLISECONDS)
        override fun close() { input.offer(ByteArray(0)) }
    }

    private enum class Mode { WIRED, WIRELESS, TUNNEL, WRAPPED }
    private companion object { val FRAME = Iap2Frame(0x4300, byteArrayOf(0, 4, 0, 0)) }
}

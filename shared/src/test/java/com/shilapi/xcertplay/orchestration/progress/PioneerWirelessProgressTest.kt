package com.shilapi.xcertplay.orchestration.progress

import org.junit.Assert.*
import org.junit.Test

class PioneerWirelessProgressTest {
    @Test fun earlyBluetoothFailureMarksBluetoothWithoutCompletingNetworkPreparation() {
        val p=progress()
        p.failWirelessStep(ConnectionStep.DEVICE)
        val result=p.snapshot()
        assertEquals(ConnectionStep.DEVICE,result.current)
        assertEquals(ConnectionOutcome.FAILED,result.outcome)
        assertFalse(ConnectionStep.NETWORK in result.visited)
        p.completeWirelessStep(ConnectionStep.PREPARATION)
        assertEquals(ConnectionStep.DEVICE,p.snapshot().current)
    }
    @Test fun pendingLayoutMatchesTheStartedAttemptForEveryTransport() {
        for ((wireless, channel) in listOf(false to ConnectionChannel.FRAMEWORK,
            false to ConnectionChannel.VPN, true to ConnectionChannel.WIRELESS,
            true to ConnectionChannel.PIONEER_WIRELESS)) {
            val pending = ConnectionProgress.stepsFor(wireless, channel)
            val started = ConnectionProgress("test", wireless = wireless, channel = channel).snapshot().steps
            assertEquals(started, pending)
            assertEquals(if (wireless) ConnectionStep.NETWORK else ConnectionStep.DEVICE, pending[1])
            assertEquals(channel == ConnectionChannel.PIONEER_WIRELESS,
                ConnectionStep.WIRELESS_JOIN in pending)
        }
    }

    private fun progress(channel: ConnectionChannel=ConnectionChannel.PIONEER_WIRELESS)=
        ConnectionProgress("test",wireless=channel!=ConnectionChannel.VPN,channel=channel,clock={ 100L })
    @Test fun parallelFactsWaitForAllPredecessorsBeforeShowingVideo() {
        val p=progress()
        val order=p.snapshot().steps
        p.firstFrame()
        order.drop(1).reversed().forEach { p.completeWirelessStep(it) }
        assertEquals(ConnectionStep.PREPARATION,p.snapshot().current)
        assertEquals(ConnectionOutcome.CONNECTING,p.snapshot().outcome)
        p.completeWirelessStep(ConnectionStep.PREPARATION)
        assertEquals(ConnectionStep.VIDEO,p.snapshot().current)
        assertEquals(ConnectionOutcome.COMPLETE,p.snapshot().outcome)
        assertEquals(order.toSet(),p.snapshot().visited)
    }
    @Test fun firstFrameAndHandshakeCompletionCanArriveConcurrently() {
        val workers=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            repeat(50) {
                val p=progress()
                val go=java.util.concurrent.CountDownLatch(1)
                val frame=workers.submit { go.await();p.firstFrame() }
                val handshake=workers.submit { go.await();p.snapshot().steps.forEach { p.completeWirelessStep(it) } }
                go.countDown()
                frame.get(2,java.util.concurrent.TimeUnit.SECONDS)
                handshake.get(2,java.util.concurrent.TimeUnit.SECONDS)
                assertEquals(ConnectionOutcome.COMPLETE,p.snapshot().outcome)
            }
        } finally { workers.shutdownNow() }
    }
    @Test fun cancellationCannotBeOverriddenByLateCallbacks() {
        val p=progress();p.stop(ConnectionOutcome.CANCELLED)
        p.snapshot().steps.forEach { p.completeWirelessStep(it) };p.firstFrame()
        assertEquals(ConnectionOutcome.CANCELLED,p.snapshot().outcome)
        assertFalse(p.hasRendered)
    }
    @Test fun wiredAndOrdinaryWirelessKeepTheirSixStepsAndDirectProgress() {
        for(channel in listOf(ConnectionChannel.VPN,ConnectionChannel.WIRELESS)) {
            val p=progress(channel)
            assertEquals(6,p.snapshot().steps.size)
            p.step(ConnectionStep.CONTROL)
            assertEquals(ConnectionStep.CONTROL,p.snapshot().current)
            p.firstFrame();assertEquals(ConnectionOutcome.COMPLETE,p.snapshot().outcome)
        }
    }
    @Test fun vehicleModeHasNoUsbOrVpnPermissionTasks() {
        val tasks=connectionTasks(ConnectionChannel.PIONEER_WIRELESS)
        assertTrue(ConnectionTask.ROOT in tasks)
        assertTrue(ConnectionTask.SESSION_AUTH in tasks)
        assertFalse(ConnectionTask.USB_PERMISSION in tasks)
        assertFalse(ConnectionTask.VPN_PERMISSION in tasks)
    }
}

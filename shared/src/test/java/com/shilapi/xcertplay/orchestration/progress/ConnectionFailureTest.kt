package com.shilapi.xcertplay.orchestration.progress

import com.shilapi.xcertplay.network.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionFailureTest {
    @Test fun typedBluetoothTimeoutIsDistinctFromAnUnconnectedPhone() {
        val timeout = connectionFailure(WirelessStartupException(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,
            "private name / address", 120_000), ConnectionStep.NETWORK)
        assertEquals("device", timeout.stage)
        assertEquals(120_000L, timeout.thresholdMillis)
        assertNotNull(timeout.timeoutKind)
        val absent = connectionFailure(WirelessStartupException(WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED,
            "private name / address"), ConnectionStep.DEVICE)
        assertNull(absent.timeoutKind)
        assertFalse(timeout.toString().contains("private"))
    }
    @Test fun arbitraryErrorsAreNotClassifiedByTheirText() {
        val failure = connectionFailure(IllegalStateException("timeout SSID=secret"), ConnectionStep.PREPARATION)
        assertEquals("unknown", failure.code)
        assertNull(failure.timeoutKind)
        assertFalse(failure.toString().contains("secret"))
    }
    @Test fun observerFailureCannotChangeTheConnectionOrFilterLongSuccess() {
        var now = 0L
        val progress = ConnectionProgress("test", true, clock = { now })
        progress.observeTelemetry { throw IllegalStateException("analytics unavailable") }
        progress.step(ConnectionStep.DEVICE, true)
        now = 200_000
        progress.firstFrame()
        assertEquals(ConnectionOutcome.COMPLETE, progress.snapshot().outcome)
        assertEquals(200_000L, progress.snapshot().userWaitMillis)
    }
    @Test fun subscriptionCapturesTheLatestStateAndThenObservesTheNextMutation() {
        var now = 0L
        val progress = ConnectionProgress("test", true, clock = { now })
        progress.step(ConnectionStep.DEVICE)
        val observations = mutableListOf<ConnectionProgressSnapshot>()
        progress.observeTelemetry { observations += it }
        now = 100
        progress.firstFrame()
        assertEquals(ConnectionStep.DEVICE, observations.first().current)
        assertEquals(ConnectionOutcome.COMPLETE, observations.last().outcome)
        assertEquals(100L, observations.last().elapsedMillis)
    }
}

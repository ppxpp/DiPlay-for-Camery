package com.shilapi.xcertplay.analytics

import com.shilapi.xcertplay.orchestration.progress.*
import org.junit.Assert.*
import org.junit.Test

class ConnectionTelemetryTest {
    private var now = 0L
    private val events = mutableListOf<ConnectionEvent>()
    private var pending: PendingConnection? = null
    private val recorder = ConnectionTelemetry({ now }, events::add) { pending = it }
    private val dimensions = mapOf("transport" to "wireless", "conn_backend" to "pioneer_wireless")
    private fun progress() = ConnectionProgress("test", true, clock = { now })
    private fun begin(token: Long = 1): ConnectionProgress = progress().also {
        recorder.accept(dimensions, "manual")
        recorder.begin(token, dimensions)
    }
    private fun rows(name: String) = events.filter { it.name == name }
    private fun fail(progress: ConnectionProgress, timeout: Boolean = false) {
        progress.recordFailure(ConnectionFailure(if (timeout) "network_timeout" else "unknown", "network",
            if (timeout) "stage_deadline" else null, if (timeout) 100 else null))
        progress.stop(ConnectionOutcome.FAILED)
        recorder.observe(1, progress.snapshot())
    }

    @Test fun oneStartAndOneSuccessDespiteRepeatedCallbacks() {
        val p = begin()
        now = 100
        p.firstFrame()
        repeat(3) { recorder.observe(1, p.snapshot()); recorder.complete(1) }
        assertEquals(1, rows("cp_conn_start").size)
        assertEquals(1, rows("cp_conn_attempt_end").size)
        assertEquals("success", rows("cp_conn_result").single().attributes["result"])
        assertEquals(100L, rows("cp_conn_result").single().value)
        assertEquals(0L, rows("cp_conn_retry_count").single().value)
        assertNull(pending)
    }

    @Test fun timeoutThenRetrySuccessIsOneRequestAndTwoAttempts() {
        val p = begin()
        now = 100; fail(p, true)
        recorder.observe(1, p.snapshot())
        assertTrue(rows("cp_conn_result").isEmpty())
        recorder.retry(1)
        now = 150
        val next = progress()
        recorder.begin(2, dimensions)
        recorder.observe(1, p.snapshot()) // stale old callbacks cannot alter the new attempt
        now = 200; next.firstFrame(); recorder.observe(2, next.snapshot())
        assertEquals(1, rows("cp_conn_start").size)
        assertEquals(listOf("failed", "success"), rows("cp_conn_attempt_end").map { it.attributes["result"] })
        assertEquals(listOf(100L, 50L), rows("cp_conn_attempt_end").map { it.value })
        assertEquals(1, rows("cp_conn_timeout").size)
        assertEquals(1L, rows("cp_conn_timeout_count").single().value)
        assertEquals(1L, rows("cp_conn_retry_count").single().value)
        assertEquals(200L, rows("cp_conn_result").single().value)
        assertEquals(50L, rows("cp_conn_wait_duration").single { it.attributes["wait_kind"] == "between_attempts" }.value)
    }

    @Test fun retryScheduledButNotExecutedDoesNotIncrementRetryCount() {
        val p = begin(); now = 100; fail(p)
        recorder.retry(1)
        now = 120; recorder.cancelOwned(1, "user_cancel")
        assertEquals(0L, rows("cp_conn_retry_count").single().value)
        assertEquals("cancelled", rows("cp_conn_result").single().attributes["result"])
    }

    @Test fun provisionalFailureCanStillBecomeSuccessWithoutInventingARetry() {
        val p = begin(); now = 100; fail(p, true); recorder.retry(1)
        now = 130; recorder.complete(1)
        assertEquals(listOf("success"), rows("cp_conn_attempt_end").map { it.attributes["result"] })
        assertEquals("success", rows("cp_conn_result").single().attributes["result"])
        assertEquals(0L, rows("cp_conn_retry_count").single().value)
    }

    @Test fun finalFailureAndManualRetryAreSeparateRequests() {
        val p = begin(); now = 100; fail(p); recorder.fail(1)
        recorder.accept(dimensions, "manual_retry", replace = true)
        recorder.begin(2, dimensions); now = 150; recorder.complete(2)
        assertEquals(listOf("manual", "manual_retry"), rows("cp_conn_start").map { it.attributes["trigger_source"] })
        assertEquals(listOf("failed", "success"), rows("cp_conn_result").map { it.attributes["result"] })
    }

    @Test fun postSuccessLossStartsANewAutomaticReconnectRequestAtTheDecisionTime() {
        begin(); now = 100; recorder.complete(1)
        now = 110; recorder.retry(1)
        now = 160; recorder.begin(2, dimensions)
        now = 200; recorder.complete(2)
        assertEquals(listOf("manual", "auto_reconnect"), rows("cp_conn_start").map { it.attributes["trigger_source"] })
        assertEquals(listOf(100L, 90L), rows("cp_conn_result").map { it.value })
        assertEquals(listOf(0L, 0L), rows("cp_conn_retry_count").map { it.value })
    }

    @Test fun lateOwnerCancellationCannotCancelANewerAttempt() {
        val p = begin(); now = 100; fail(p); recorder.retry(1)
        recorder.begin(2, dimensions)
        recorder.cancelOwned(1, "user_cancel"); recorder.cancelOwned(null, "user_cancel")
        assertTrue(rows("cp_conn_result").isEmpty())
        now = 200; recorder.complete(2)
        assertEquals("success", rows("cp_conn_result").single().attributes["result"])
    }

    @Test fun longExternalWaitIsStillIncludedAlthoughLocalHistoryRejectsIt() {
        val p = begin()
        p.step(ConnectionStep.DEVICE, true)
        now = 200_000
        p.firstFrame(); recorder.observe(1, p.snapshot())
        assertEquals(200_000L, rows("cp_conn_result").single().value)
        assertEquals(200_000L, rows("cp_conn_wait_duration").single { it.attributes["wait_kind"] == "external_condition" }.value)
    }

    @Test fun parallelPeerIsCancelledRatherThanReportedAsAnotherFailure() {
        val p = begin()
        p.task(ConnectionTask.WIRELESS_LINK, ConnectionTaskState.RUNNING)
        p.task(ConnectionTask.WIRELESS_NETWORK, ConnectionTaskState.RUNNING)
        now = 100
        p.recordFailure(ConnectionFailure("bluetooth_not_connected", "device", "stage_deadline", 100))
        p.stop(ConnectionOutcome.FAILED); recorder.observe(1, p.snapshot()); recorder.fail(1)
        val tasks = rows("cp_conn_stage_end").filter { it.attributes["stage_kind"] == "technical_task" }
        assertEquals("failed", tasks.single { it.attributes["stage"] == "wireless-link" }.attributes["stage_result"])
        assertEquals("cancelled", tasks.single { it.attributes["stage"] == "wireless-network" }.attributes["stage_result"])
        assertEquals(1, rows("cp_conn_timeout").size)
    }

    @Test fun overlappingTaskDurationsAreIndependentAndNotSummedAsTotal() {
        val p = begin()
        p.task(ConnectionTask.WIRELESS_LINK, ConnectionTaskState.RUNNING)
        p.task(ConnectionTask.WIRELESS_NETWORK, ConnectionTaskState.RUNNING)
        now = 80; p.task(ConnectionTask.WIRELESS_LINK, ConnectionTaskState.COMPLETE)
        now = 100; p.task(ConnectionTask.WIRELESS_NETWORK, ConnectionTaskState.COMPLETE)
        p.firstFrame(); recorder.observe(1, p.snapshot())
        val tasks = rows("cp_conn_stage_end").filter { it.attributes["stage"] in setOf("wireless-link", "wireless-network") }
        assertEquals(180L, tasks.sumOf { it.value ?: 0 })
        assertEquals(100L, rows("cp_conn_result").single().value)
        assertEquals(tasks.size, tasks.map { it.attributes["stage"] }.toSet().size)
    }

    @Test fun interruptedRecordIsUnknownAndDoesNotIncludeTimeSpentPoweredOff() {
        val p = begin(); now = 50; recorder.observe(1, p.snapshot())
        val saved = requireNotNull(pending)
        now = 50_000
        val restored = mutableListOf<ConnectionEvent>()
        ConnectionTelemetry({ now }, restored::add) {}.recover(saved)
        val result = restored.single { it.name == "cp_conn_result" }
        assertEquals("interrupted_unknown", result.attributes["result"])
        assertEquals("false", result.attributes["duration_complete"])
        assertEquals(50L, result.value)
    }

    @Test fun metricsUseFactTimestampsInsteadOfDelayedWorkerProcessingTime() {
        recorder.accept(dimensions, "manual", atMillis = 10)
        now = 5000
        recorder.begin(1, dimensions, atMillis = 20)
        recorder.complete(1, atMillis = 120)
        assertEquals(110L, rows("cp_conn_result").single().value)
        assertEquals(100L, rows("cp_conn_attempt_end").single().value)
    }

    @Test fun factsAreCategoricalAndDoNotForwardPrivateValues() {
        val p = begin()
        p.fact("ssid", "Private-hotspot")
        p.fact("bt_initial_state", "12:34:56:78:90:AB")
        p.fact("hotspot_state", "retained")
        now = 100; p.firstFrame(); recorder.observe(1, p.snapshot())
        val attrs = rows("cp_conn_result").single().attributes
        assertEquals("retained", attrs["hotspot_state"])
        assertFalse(attrs.containsKey("ssid")); assertFalse(attrs.containsKey("bt_initial_state"))
        assertTrue(events.none { it.attributes.values.any { value -> value.contains("Private") || value.contains("12:34") } })
    }
    @Test fun cancelDuringAutomaticReconnectBackoffUsesThePredecessorOwner() {
        begin(); now = 100; recorder.complete(1)
        now = 110; recorder.retry(1)
        now = 120; recorder.cancelOwned(1, "user_cancel")
        assertEquals(listOf("success", "cancelled"), rows("cp_conn_result").map { it.attributes["result"] })
        assertNull(pending)
    }

    @Test fun cancelDuringManualReplacementTeardownIsNotAnUnknownInterruption() {
        begin(); now = 100
        recorder.accept(dimensions, "manual_retry", replace = true, predecessorToken = 1)
        now = 120; recorder.cancelOwned(1, "user_cancel")
        assertEquals(listOf("cancelled", "cancelled"), rows("cp_conn_result").map { it.attributes["result"] })
        assertNull(pending)
    }

    @Test fun aTaskWithoutAnObservedStartIsNotATimingSample() {
        val p = begin()
        p.task(ConnectionTask.WIRELESS_LINK, ConnectionTaskState.COMPLETE)
        now = 100; p.firstFrame(); recorder.observe(1, p.snapshot())
        val link = rows("cp_conn_stage_end").single { it.attributes["stage"] == "wireless-link" }
        assertEquals("false", link.attributes["timing_observed"])
    }
    @Test fun teardownFailureDuringAutomaticReconnectIsARealFinalFailure() {
        begin(); now = 100; recorder.complete(1)
        now = 110; recorder.retry(1)
        now = 130; recorder.fail(1, "teardown_incomplete")
        assertEquals(listOf("success", "failed"), rows("cp_conn_result").map { it.attributes["result"] })
        assertEquals("preparation", rows("cp_conn_result").last().attributes["failed_stage"])
        assertNull(pending)
    }

    @Test fun actualPioneerRecoveryCompletesDespiteTheDisplayOnlyRetryState() {
        val p = begin()
        now = 100; fail(p); recorder.retry(1)
        p.stop(ConnectionOutcome.RETRYING)
        now = 120; p.firstFrame()
        recorder.observe(1, p.snapshot())
        assertTrue(rows("cp_conn_result").isEmpty()) // a frame alone is insufficient before Wi-Fi iAP2 readiness
        p.renderedConnectionReady()
        recorder.observe(1, p.snapshot())
        assertEquals("success", rows("cp_conn_result").single().attributes["result"])
        assertEquals(120L, rows("cp_conn_result").single().value)
        assertEquals(0L, rows("cp_conn_retry_count").single().value)
    }
}

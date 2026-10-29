package com.shilapi.xcertplay.analytics

import com.shilapi.xcertplay.orchestration.progress.*
import java.util.UUID

internal data class ConnectionEvent(val name: String, val attributes: Map<String, String>, val value: Long? = null)
internal data class PendingConnection(
    val localId: String, val attributes: Map<String, String>, val attempts: Int,
    val timeouts: Int, val elapsedMillis: Long, val attemptMillis: Long, val stage: String,
)

/** One accepted request, many attempts. Has no Android, SDK, view or controller references. */
internal class ConnectionTelemetry(
    private val clock: () -> Long,
    private val emit: (ConnectionEvent) -> Unit,
    private val checkpoint: (PendingConnection?) -> Unit,
) {
    private data class Request(
        val id: String, val started: Long, val attributes: MutableMap<String, String>,
        var attempts: Int = 0, var timeouts: Int = 0, var waitMillis: Long = 0,
        var betweenAttemptsMillis: Long = 0, var betweenSince: Long? = null, var lastAt: Long = started,
        var predecessorToken: Long? = null,
    )
    private data class Attempt(
        val token: Long, val started: Long, var snapshot: ConnectionProgressSnapshot? = null,
        var failure: ConnectionFailure? = null, var ended: Boolean = false, var timeoutReported: Boolean = false,
        val stages: MutableSet<String> = hashSetOf(),
    )
    private var request: Request? = null
    private var attempt: Attempt? = null
    private var requestsInProcess = 0
    var nextSource: String = "external_entry"
    private var lastFailure: ConnectionFailure? = null
    private var lastSuccessfulToken: Long? = null
    private var lastSuccessfulAttributes: Map<String, String> = emptyMap()

    fun accept(attributes: Map<String, String>, source: String, replace: Boolean = false, atMillis: Long = clock(), predecessorToken: Long? = null) {
        if (request != null && !replace) return
        val previousOwner = predecessorToken ?: attempt?.token
        if (request != null) cancel("replaced", atMillis)
        val attrs = attributes.toMutableMap().apply {
            put("schema_version", "1")
            put("trigger_source", source.takeIf { it in SOURCES } ?: "external_entry")
            put("process_state", if (requestsInProcess++ == 0) "first_connection" else "subsequent_connection")
        }
        request = Request(UUID.randomUUID().toString(), atMillis, attrs, predecessorToken = previousOwner)
        attempt = null
        lastFailure = null
        emit(ConnectionEvent("cp_conn_start", attrs.toMap()))
        persist()
    }

    fun begin(token: Long, attributes: Map<String, String>, atMillis: Long = clock()) {
        if (attempt?.token == token) return
        if (request == null) accept(attributes, nextSource, atMillis = atMillis)
        val r = request ?: return
        attempt?.takeIf { !it.ended }?.let { endAttempt(it, if (r.betweenSince != null) "failed" else "cancelled", atMillis = atMillis) }
        r.betweenSince?.let { r.betweenAttemptsMillis += (atMillis - it).coerceAtLeast(0) }
        r.betweenSince = null
        r.attributes.putAll(attributes)
        if (r.attempts == 0) emit(ConnectionEvent("cp_conn_stage_end", attrs() + mapOf(
            "stage_kind" to "technical_task", "stage" to "host_preflight", "stage_result" to "complete", "timing_observed" to "true"),
            (atMillis - r.started).coerceAtLeast(0)))
        r.attempts++
        attempt = Attempt(token, atMillis)
        r.lastAt = atMillis
        persist()
    }

    fun observe(token: Long, snapshot: ConnectionProgressSnapshot, atMillis: Long = clock()) {
        val a = attempt?.takeIf { it.token == token } ?: return
        val r = request ?: return
        if (a.ended) return
        r.lastAt = atMillis
        a.snapshot = snapshot
        snapshot.failure?.let { a.failure = it; lastFailure = it }
        snapshot.facts.forEach { (key, value) ->
            if (FACTS[key]?.contains(value) == true) r.attributes[key] = value
        }
        val failure = a.failure
        val timeoutKind = failure?.timeoutKind
        if (!a.timeoutReported && failure != null && timeoutKind != null) {
            a.timeoutReported = true
            r.timeouts++
            val actual = snapshot.tasks.filter { it.task.step.key == failure.stage }.maxOfOrNull { it.elapsedMillis }
                ?: snapshot.durations.entries.firstOrNull { it.key.key == failure.stage }?.value ?: snapshot.elapsedMillis
            emit(ConnectionEvent("cp_conn_timeout", attrs() + mapOf(
                "stage" to failure.stage, "timeout_kind" to timeoutKind,
                "threshold_ms" to (failure.thresholdMillis?.toString() ?: "unknown"),
                "failure_code" to failure.code,
            ), actual.coerceAtLeast(0)))
        }
        if (snapshot.outcome == ConnectionOutcome.COMPLETE || snapshot.renderedConnectionReady) complete(token, atMillis)
        if (request != null) persist()
    }

    fun complete(token: Long, atMillis: Long = clock()) {
        val a = attempt?.takeIf { it.token == token && !it.ended } ?: return
        request?.betweenSince = null
        endAttempt(a, "success", atMillis = atMillis)
        finish("success", atMillis = atMillis)
    }

    fun retry(token: Long, atMillis: Long = clock()) {
        if (request == null) {
            if (token == lastSuccessfulToken) {
                nextSource = "auto_reconnect"
                accept(lastSuccessfulAttributes, "auto_reconnect", atMillis = atMillis, predecessorToken = token)
            }
            return
        }
        val a = attempt?.takeIf { it.token == token } ?: return
        request?.let { it.lastAt = atMillis; if (it.betweenSince == null) it.betweenSince = atMillis }
        persist()
    }

    fun fail(token: Long?, code: String? = null, atMillis: Long = clock()) {
        if (request == null || token == null && attempt != null || token != null &&
            token != (attempt?.token ?: request?.predecessorToken)) return
        attempt?.let { if (!it.ended) endAttempt(it, "failed", if (it.failure == null) code else null, atMillis) }
        finish("failed", code, atMillis)
    }

    fun cancelOwned(token: Long?, code: String, atMillis: Long = clock()) {
        if (token == null && attempt != null || token != null &&
            token != (attempt?.token ?: request?.predecessorToken)) return
        cancel(code, atMillis)
    }

    fun cancel(code: String = "user_cancel", atMillis: Long = clock()) {
        if (request == null) return
        attempt?.let { if (!it.ended) endAttempt(it, if (it.failure != null || it.snapshot?.outcome in setOf(ConnectionOutcome.FAILED, ConnectionOutcome.RETRYING)) "failed" else "cancelled", code, atMillis) }
        finish("cancelled", code, atMillis)
    }

    fun recover(pending: PendingConnection) {
        // Recovered time is a lower bound from the last checkpoint, never time spent powered off.
        val attrs = pending.attributes + mapOf("result" to "interrupted_unknown", "failure_code" to "interrupted_unknown",
            "failed_stage" to pending.stage, "duration_complete" to "false",
            "attempt_bucket" to bucket(pending.attempts), "retry_bucket" to bucket((pending.attempts - 1).coerceAtLeast(0)),
            "timeout_bucket" to bucket(pending.timeouts))
        checkpoint(null)
        if (pending.attempts > 0) emit(ConnectionEvent("cp_conn_attempt_end", attrs, pending.attemptMillis))
        emit(ConnectionEvent("cp_conn_result", attrs, pending.elapsedMillis))
        emit(ConnectionEvent("cp_conn_retry_count", attrs, (pending.attempts - 1).coerceAtLeast(0).toLong()))
        emit(ConnectionEvent("cp_conn_timeout_count", attrs, pending.timeouts.toLong()))
    }

    fun forget() { request = null; attempt = null; lastFailure = null; lastSuccessfulToken = null; lastSuccessfulAttributes = emptyMap(); nextSource = "external_entry" }

    private fun stage(a: Attempt, kind: String, id: String, result: String, millis: Long, observed: Boolean = true) {
        if (a.stages.add("$kind:$id")) emit(ConnectionEvent("cp_conn_stage_end",
            attrs() + mapOf("stage_kind" to kind, "stage" to id, "stage_result" to result, "timing_observed" to observed.toString()), millis.coerceAtLeast(0)))
    }

    private fun endAttempt(a: Attempt, result: String, code: String? = null, atMillis: Long = clock()) {
        if (a.ended) return
        a.ended = true
        a.snapshot?.let { snapshot ->
            snapshot.visited.forEach { step ->
                val state = when {
                    result == "success" -> "complete"
                    step != snapshot.current -> "complete"
                    result == "cancelled" -> "cancelled"
                    a.failure != null && a.failure?.stage != step.key -> "cancelled"
                    else -> "failed"
                }
                stage(a, "ui_step", step.key, state, snapshot.durations[step] ?: 0,
                    !(result == "success" && snapshot.outcome != ConnectionOutcome.COMPLETE && step == snapshot.current))
            }
            snapshot.tasks.filter { it.state != ConnectionTaskState.PENDING }.forEach { task ->
                val state = when (task.state) {
                    ConnectionTaskState.FAILED -> when {
                        result == "success" -> "recovered"
                        a.failure != null && task.task.step.key != a.failure?.stage -> "cancelled"
                        else -> "failed"
                    }
                    ConnectionTaskState.RUNNING, ConnectionTaskState.RETRYING -> "cancelled"
                    else -> task.state.name.lowercase()
                }
                stage(a, "technical_task", task.task.id, state, task.elapsedMillis, task.observedStart && !(result == "success" && task.state == ConnectionTaskState.FAILED))
            }
            snapshot.measuredTasks.forEach { stage(a, "technical_task", it.id, it.result, it.durationMillis) }
        }
        val duration = if (result == "success") (atMillis - a.started).coerceAtLeast(0)
            else a.snapshot?.elapsedMillis ?: (atMillis - a.started).coerceAtLeast(0)
        request?.waitMillis = (request?.waitMillis ?: 0) + (a.snapshot?.userWaitMillis ?: 0)
        emit(ConnectionEvent("cp_conn_attempt_end", attrs() + mapOf("result" to result,
            "failure_code" to (if (result == "success") "none" else code ?: a.failure?.code ?: "unknown"),
            "failed_stage" to (if (result == "success") "none" else a.failure?.stage ?: a.snapshot?.current?.key ?: "unknown")), duration))
    }

    private fun finish(result: String, code: String? = null, atMillis: Long = clock()) {
        val r = request ?: return
        val attrs = attrs() + mapOf("result" to result, "duration_complete" to "true",
            "first_try" to (result == "success" && r.attempts == 1).toString(),
            "attempt_bucket" to bucket(r.attempts), "retry_bucket" to bucket((r.attempts - 1).coerceAtLeast(0)),
            "timeout_bucket" to bucket(r.timeouts),
            "failure_code" to (if (result == "success") "none" else code ?: lastFailure?.code ?: "unknown"),
            "failed_stage" to (if (result == "success") "none" else if (code in setOf("teardown_incomplete", "invalid_configuration")) "preparation"
                else lastFailure?.stage ?: attempt?.snapshot?.current?.key ?: "preparation"))
        val duration = (atMillis - r.started).coerceAtLeast(0)
        if (result == "success") {
            lastSuccessfulToken = attempt?.token
            lastSuccessfulAttributes = r.attributes.toMap()
        } else {
            lastSuccessfulToken = null
            lastSuccessfulAttributes = emptyMap()
        }
        if (r.attempts == 0) emit(ConnectionEvent("cp_conn_stage_end", attrs + mapOf(
            "stage_kind" to "technical_task", "stage" to "host_preflight",
            "stage_result" to (if (result == "failed") "failed" else "cancelled"), "timing_observed" to "true"), duration))
        checkpoint(null)
        emit(ConnectionEvent("cp_conn_result", attrs, duration))
        emit(ConnectionEvent("cp_conn_retry_count", attrs, (r.attempts - 1).coerceAtLeast(0).toLong()))
        emit(ConnectionEvent("cp_conn_timeout_count", attrs, r.timeouts.toLong()))
        emit(ConnectionEvent("cp_conn_wait_duration", attrs + ("wait_kind" to "external_condition"), r.waitMillis))
        emit(ConnectionEvent("cp_conn_wait_duration", attrs + ("wait_kind" to "between_attempts"),
            r.betweenAttemptsMillis + (r.betweenSince?.let { (atMillis - it).coerceAtLeast(0) } ?: 0)))
        request = null
        attempt = null
    }

    private fun attrs(): Map<String, String> = request?.let {
        it.attributes.toMap() + ("attempt_bucket" to bucket(it.attempts))
    }.orEmpty()

    private fun persist() {
        val r = request ?: return
        val a = attempt
        checkpoint(PendingConnection(r.id, r.attributes.toMap(), r.attempts, r.timeouts,
            (r.lastAt - r.started).coerceAtLeast(0), a?.snapshot?.elapsedMillis ?: 0,
            a?.failure?.stage ?: a?.snapshot?.current?.key ?: "preparation"))
    }

    companion object {
        val SOURCES = setOf("manual", "manual_retry", "app_auto", "boot_auto", "bluetooth_auto", "usb_attach",
            "auto_reconnect", "configuration_change", "external_entry")
        private fun bucket(value: Int) = if (value >= 6) "6_plus" else value.coerceAtLeast(0).toString()
        val FACTS = mapOf("hotspot_state" to setOf("created", "retained", "existing", "managed", "unknown"),
            "bt_initial_state" to setOf("connected", "waiting", "unknown"),
            "wifi_band" to setOf("2_4ghz", "5ghz", "6ghz", "unknown"))
    }
}

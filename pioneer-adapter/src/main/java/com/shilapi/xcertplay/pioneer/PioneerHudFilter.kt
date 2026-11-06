package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/** Only navigation-worker maintenance renews the lease; asynchronous IO never invents a heartbeat. */
internal class PioneerHudFilter(
    context: Context,
    private val rootRequest: ((String, JSONObject, () -> Boolean) -> JSONObject)? = null,
) {
    private val app = context.applicationContext
    private val inFlight = AtomicBoolean()
    @Volatile private var feedback = JSONObject()
    @Volatile private var launchError: String? = null
    @Volatile private var requestedActive = false
    @Volatile private var released = true
    @Volatile private var generation = 0L
    @Volatile private var nextInspectionMs = 0L
    @Volatile private var lastWriteMs = -HEARTBEAT_MS
    @Volatile private var writtenDeadline = 0L
    private val main = Handler(Looper.getMainLooper())
    private var probe = 0L
    private var probePending = false
    @Volatile private var mainAck = 0L
    @Volatile private var mainAckMs = 0L

    @Synchronized
    fun maintain(active: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!active && released) return
        if (active != requestedActive) {
            requestedActive = active
            if (active) released = false
            generation++
            nextInspectionMs = 0
            writtenDeadline = 0
            lastWriteMs = -HEARTBEAT_MS
        }
        val renew = active && now - lastWriteMs >= HEARTBEAT_MS && mainResponsive(now)
        if (!renew && now < nextInspectionMs) return
        if (!inFlight.compareAndSet(false, true)) return
        val version = generation
        val issue = now // Never derive/refresh this timestamp from the IO thread.
        nextInspectionMs = now + HEARTBEAT_MS
        Thread({
            try {
                val response = if (!active) {
                    request("HUD_RELEASE")
                } else {
                    // Idempotent START attaches to this owner's service; no second Java/root process.
                    request("HUD_START")
                    if (version != generation || !requestedActive) {
                        request("HUD_RELEASE")
                    } else if (renew) {
                        request("HUD_LEASE", JSONObject().put("active", true).put("issuedMs", issue)).also {
                            synchronized(this) {
                                if (version == generation && requestedActive) {
                                    lastWriteMs = issue
                                    writtenDeadline = issue + TIMEOUT_MS
                                }
                            }
                        }
                    } else request("HUD_STATUS")
                }
                synchronized(this) {
                    if (version == generation) {
                        feedback = response
                        if (!active) released = true
                        launchError = if (response.isNull("error")) null else response.optString("error").ifEmpty { null }
                        // Acknowledgement polling never renews the navigation-worker lease.
                        if (active && (!response.optBoolean("ready") ||
                                response.optLong("readyUntilElapsedMs") < writtenDeadline) && response.optString("state") != "ERROR")
                            nextInspectionMs = SystemClock.elapsedRealtime() + 250
                    }
                }
            } catch (error: Exception) {
                synchronized(this) {
                    if (version == generation) {
                        launchError = error.toString()
                        // A missed acknowledgement cannot revoke the previous confirmed lease early.
                        // Keep its original expiry on timeouts; explicit failures still revoke readiness.
                        if (!renewalTimedOut(error)) {
                            feedback = JSONObject()
                            writtenDeadline = 0
                        }
                        nextInspectionMs = SystemClock.elapsedRealtime() + 1000
                    }
                }
            } finally {
                synchronized(this) {
                    inFlight.set(false)
                    if (version != generation) {
                        nextInspectionMs = 0
                        // release() can be the last navigation-worker call; do not lose it behind IO.
                        if (!requestedActive) maintain(false)
                    }
                }
            }
        }, "diplay-hud-control").apply { isDaemon = true; start() }
    }

    private fun request(op: String, payload: JSONObject = JSONObject()): JSONObject {
        val until = SystemClock.elapsedRealtime() + REQUEST_TIMEOUT_MS
        // Bound bootstrap as well as socket IO; the root request's timeout starts after ready().
        val current = { SystemClock.elapsedRealtime() < until }
        return rootRequest?.invoke(op, payload, current)
            ?: PioneerRootSession.request(app, op, payload, REQUEST_TIMEOUT_MS, current)
    }

    private fun renewalTimedOut(error: Throwable): Boolean =
        generateSequence(error) { it.cause }.any {
            it is java.net.SocketTimeoutException ||
                it is IllegalStateException && it.message in setOf("ROOT_IPC_CANCELLED", "ROOT_IPC_DEADLINE_EXCEEDED")
        }

    fun ready(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return requestedActive && now < writtenDeadline && feedback.optBoolean("ready") &&
            now < feedback.optLong("readyUntilElapsedMs")
    }

    @Synchronized
    fun release() {
        maintain(false)
        writtenDeadline = 0
    }

    private fun mainResponsive(now: Long): Boolean {
        if (!probePending || mainAck == probe && now - mainAckMs > 1000) {
            val next = ++probe
            probePending = true
            main.post { mainAckMs = SystemClock.elapsedRealtime(); mainAck = next }
        }
        return mainAck == probe && now - mainAckMs in 0..1000
    }

    fun snapshot(): JSONObject {
        val state = feedback
        return JSONObject().put("ready", ready()).put("requested", requestedActive)
            .put("heartbeatMs", HEARTBEAT_MS).put("timeoutMs", TIMEOUT_MS)
            .put("expiresAtElapsedMs", writtenDeadline).put("error", launchError ?: JSONObject.NULL)
            .put("blockedFrames", state.optLong("blockedFrames")).put("blockedExits", state.optLong("blockedExits"))
            .put("tpsPid", state.optInt("tpsPid"))
    }

    companion object {
        private const val REQUEST_TIMEOUT_MS = 1500L
        private const val HEARTBEAT_MS = PioneerRootHudService.HEARTBEAT_MS
        private const val TIMEOUT_MS = PioneerRootHudService.TIMEOUT_MS
    }
}

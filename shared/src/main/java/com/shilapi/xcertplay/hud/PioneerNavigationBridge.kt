package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerHudCapture
import com.shilapi.xcertplay.pioneer.PioneerHudMapOwnership
import com.shilapi.xcertplay.pioneer.PioneerWiredBackend
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.shilapi.xcertplay.hud.PioneerGuidanceUpdates.Guidance

/** All navigation writes are serialized on this output's bounded worker. */
internal object PioneerNavigationBridge {
    private val route = BydHudRouteState()
    private val worker = NavigationOutputWorker("diplay-pioneer-navigation", ::clear)
    @Volatile private var applicable = false
    private var initialized = false
    private val ticking = AtomicBoolean()
    private val meterRefreshPending = AtomicBoolean()
    private val meterRefreshRequests = AtomicLong()
    @Volatile private var acceptsMeterRefresh = false
    // The SDK retains only a WeakReference; keep this listener alive for the process lifetime.
    private var meterListener: Any? = null
    private var listenerAttemptNs: Long? = null
    private var listenerError: String? = null
    private var meterRefreshSends = 0L
    private var ownsOutput = false
    private var relinquishedOutput = false
    @Volatile private var mapOwnership: PioneerHudMapOwnership? = null
    private var last: Guidance? = null
    private var retainedInfo: Any? = null
    private var lastError: String? = null
    private var reason = "app_open"
    private var clearPending = false
    private var clearAttempts = 0
    private var successes = 0L
    private var failures = 0L
    private var invalidFrames = 0L
    private var parsedAtMs = 0L
    private var parsedReceivedNs = 0L
    private var lastCheckpointMs = 0L
    private var lastType: Int? = null
    private val routeFrames = AtomicLong()
    private val maneuverFrames = AtomicLong()
    private val receivedAtMs = AtomicLong()
    private val updates = PioneerGuidanceUpdates()
    private val policy = CamryHudPolicy()
    @Volatile private var configured = CamryHudConfig()
    private var settingsListener: android.content.SharedPreferences.OnSharedPreferenceChangeListener? = null
    private var suppressedUpdates = 0L
    private var inputDistanceMeters: Int? = null
    private var candidateAvailable = false
    private val controllerType by lazy { Class.forName("jp.pioneer.ceam.MeterService.MeterServiceController") }
    private val infoType by lazy { Class.forName("jp.pioneer.ceam.MeterService.MeterNaviInfo") }

    fun onAppOpened(context: Context) {
        if (!PioneerWiredBackend.usesVehicleAdaptation(context)) return
        applicable = true
        synchronized(this) {
            if (mapOwnership == null) mapOwnership = PioneerHudMapOwnership(context)
        }
        synchronized(this) {
            if (settingsListener == null) {
                val app = context.applicationContext
                configured = CamryHudSettings.load(app)
                settingsListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
                    val next = CamryHudSettings.load(app)
                    if (next != configured) {
                        configured = next
                        worker.maintenance { refresh(); publish() }
                    }
                }.also { CamryHudSettings.preferences(app).registerOnSharedPreferenceChangeListener(it) }
            }
        }
        PioneerHudCapture.attach(context)
        PioneerDebugRuntime.navigationHudSnapshot = {
            val queue = worker.status()
            JSONObject(PioneerDebugRuntime.navigationHudState)
                .put("routeFrames", routeFrames.get()).put("maneuverFrames", maneuverFrames.get())
                .put("lastReceivedAtMs", receivedAtMs.get()).put("queueDepth", queue.second)
                .put("queueAccepting", queue.first).put("queueStopReason", worker.stopReason)
                .put("queueOverflows", worker.overflowCount)
                .put("observedAtMs", System.currentTimeMillis())
                .put("observedElapsedNs", android.os.SystemClock.elapsedRealtimeNanos())
        }
        if (ticking.compareAndSet(false, true)) {
            worker.maintenance {
                initialized = true
                ensureMeterListener()
                requestClear("process_recovery")
                publish()
            }
            Executors.newSingleThreadScheduledExecutor { task ->
                Thread(task, "diplay-pioneer-navigation-tick").apply { isDaemon = true }
            }.scheduleAtFixedRate({
                // Queue at most one maintenance tick behind a blocked SDK call. No flag can be
                // stranded by the frame queue being reset during session replacement.
                if (worker.status().second == 0) worker.maintenance {
                    ensureMeterListener()
                    if (clearPending && clearAttempts < 5) {
                        if (mapOwnership?.mayClear() == true) send(null)
                        else {
                            clearPending = false
                            relinquishOutput()
                            reason = "clear_yielded_to_original_map"
                        }
                    }
                    if (!clearPending) refresh()
                    publish()
                }
            }, 500, 500, TimeUnit.MILLISECONDS)
        }
    }

    fun start(context: Context) {
        onAppOpened(context)
        if (!applicable) return
        worker.start {
            initialized = true
            PioneerHudCapture.event("session_start")
            requestClear("session_start")
            publish()
        }
    }

    fun onFrame(frame: Iap2Frame) {
        if (!applicable) return
        when (frame.messageId) {
            BydHudRouteState.ROUTE_GUIDANCE_UPDATE -> routeFrames.incrementAndGet()
            BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE -> maneuverFrames.incrementAndGet()
            else -> return
        }
        val received = System.currentTimeMillis()
        val receivedNs = System.nanoTime()
        receivedAtMs.set(received)
        worker.submit {
            route.accept(frame.messageId, frame.payload)
            parsedAtMs = System.currentTimeMillis()
            if (route.lastFrameValid) parsedReceivedNs = receivedNs
            if (!route.lastFrameValid) invalidFrames++
            val apple = route.currentApple()
            lastType = apple?.type
            if (route.lastFrameValid && route.distanceUpdated && apple != null && configured.enabled) {
                route.currentManeuverIndex()?.let { index ->
                    policy.sample(CamryHudPolicy.Input(index, apple.type, apple.road, apple.distanceMeters),
                        configured, System.nanoTime())
                }
            }
            if (PioneerHudCapture.isEnabled()) PioneerHudCapture.event("frame", JSONObject().put("messageId", frame.messageId)
                .put("receivedAtMs", received).put("valid", route.lastFrameValid)
                .put("routeReason", route.diagnosticReason()).put("appleType", apple?.type ?: JSONObject.NULL)
                .put("drivingSide", apple?.drivingSide ?: JSONObject.NULL)
                .put("distanceMeters", apple?.distanceMeters ?: JSONObject.NULL).put("road", apple?.road.orEmpty().take(128))
                .put("maneuverIndex", route.currentManeuverIndex() ?: JSONObject.NULL)
                .put("payloadBytes", frame.payload.size).put("payloadTruncated", frame.payload.size > 8192)
                .put("payloadHex", payloadHex(frame.payload)))
            refresh()
            publish()
        }
    }

    fun end() { if (applicable) worker.clear() }

    private fun payloadHex(payload: ByteArray): String = buildString(minOf(payload.size, 8192) * 2) {
        val digits = "0123456789abcdef"
        for (i in 0 until minOf(payload.size, 8192)) {
            val value = payload[i].toInt() and 255
            append(digits[value shr 4]); append(digits[value and 15])
        }
    }

    private fun refresh() {
        if (!initialized) return
        if (!configured.enabled) {
            candidateAvailable = false
            policy.reset()
            reason = "disabled_in_settings"
            // Do not claim or clear an OEM-owned output just because the user toggled this setting.
            if (ownsOutput && !clearPending) requestClear(reason)
            if (!clearPending) mapOwnership?.release()
            acceptsMeterRefresh = false
            meterRefreshPending.set(false)
            return
        }
        val inputFresh = worker.status().first && System.nanoTime() - parsedReceivedNs in 0..10_000_000_000L
        val apple = route.currentApple().takeIf { inputFresh }
        val next = apple?.let {
            direction(it.type, it.drivingSide)?.let { icon ->
                val index = route.currentManeuverIndex() ?: -1
                val distance = it.distanceMeters.coerceIn(0, 1_000_000)
                val now = System.nanoTime()
                val approach = policy.evaluate(CamryHudPolicy.Input(index, it.type, it.road, distance), configured, now)
                // Before the threshold, send no navigation frame; ARROWGUIDE can also appear on HUD.
                if (approach) Guidance(index, icon, distance, it.road.take(128), "TBT") else null
            }
        }
        inputDistanceMeters = apple?.distanceMeters
        val supported = apple != null && direction(apple.type, apple.drivingSide) != null
        val freshNavigation = supported && inputFresh
        // Keep protection until our final clear completes. Renew only on this output worker.
        mapOwnership?.maintain(freshNavigation || ownsOutput || clearPending)
        candidateAvailable = supported // A valid route may be waiting for its presentation threshold.
        if (apple == null || !supported) policy.reset()
        reason = when {
            apple != null && !supported -> "unsupported_maneuver"
            supported && next == null -> "waiting_for_approach"
            else -> route.diagnosticReason()
        }
        // A previous failed clear must finish before a new route can acquire the output.
        if (clearPending) return
        if (next != null && mapOwnership?.acquire() != true) {
            relinquishOutput()
            reason = "oem_navigation_ownership_unconfirmed"
            return
        }
        if (next != last || (next == null && ownsOutput)) {
            if (next == null) requestClear(reason)
            else if (updates.shouldSend(last, next, System.nanoTime())) {
                meterRefreshPending.set(false) // This new frame also satisfies an outstanding refresh.
                send(next)
            }
            else suppressedUpdates++
        } else if (next != null && ownsOutput && meterRefreshPending.get() && updates.canRefresh(System.nanoTime())) {
            // Consume before entering the SDK so a callback during the write remains pending.
            meterRefreshPending.set(false)
            meterRefreshSends++
            PioneerHudCapture.event("meter_refresh", JSONObject().put("requests", meterRefreshRequests.get()))
            send(next)
            if (lastError != null) meterRefreshPending.set(true)
        }
        mapOwnership?.maintain(freshNavigation || clearPending)
    }

    /** Called only on the output worker; callbacks merely set an atomic flag. */
    private fun ensureMeterListener() {
        if (meterListener != null) return
        val now = System.nanoTime()
        if (listenerAttemptNs?.let { now - it < 5_000_000_000L } == true) return
        listenerAttemptNs = now
        try {
            val listener = Class.forName("com.shilapi.xcertplay.pioneer.PioneerMeterRefreshListener")
                .getConstructor(Function0::class.java).newInstance({
                    if (acceptsMeterRefresh) {
                        meterRefreshRequests.incrementAndGet()
                        meterRefreshPending.set(true)
                    }
                    Unit
                })
            val ctl = controllerType
            ctl.getMethod("registerMeterServiceListener", WeakReference::class.java)
                .invoke(ctl.getMethod("getInstance").invoke(null), WeakReference(listener))
            meterListener = listener
            listenerError = null
            PioneerHudCapture.event("meter_listener_registered")
        } catch (error: Throwable) {
            val message = (error.cause ?: error).toString()
            if (message != listenerError) Log.w("DiPlay-Pioneer-Navi", "Meter refresh listener unavailable: $message")
            listenerError = message
        }
    }

    private fun clear() {
        route.clear()
        policy.reset()
        candidateAvailable = false
        updates.reset()
        if (initialized) requestClear(worker.stopReason)
        mapOwnership?.release()
        PioneerHudCapture.event("session_end", JSONObject().put("queueOverflows", worker.overflowCount))
        publish()
    }

    private fun requestClear(cause: String) {
        acceptsMeterRefresh = false
        meterRefreshPending.set(false)
        updates.reset()
        reason = cause
        // A clear is also a Meter write: never erase a native map the user has reopened.
        if (mapOwnership?.mayClear() != true) {
            relinquishOutput()
            clearPending = false
            clearAttempts = 0
            return
        }
        clearPending = true
        clearAttempts = 0
        send(null)
    }

    private fun relinquishOutput() {
        acceptsMeterRefresh = false
        meterRefreshPending.set(false)
        if (ownsOutput) {
            relinquishedOutput = true
            PioneerHudCapture.event("meter_output_yielded")
        }
        ownsOutput = false
        last = null
        retainedInfo = null
        updates.reset()
    }

    private fun send(guidance: Guidance?) {
        if (guidance == null) clearAttempts++ else updates.attempted(System.nanoTime())
        val attempt = JSONObject().put("active", guidance != null).put("reason", reason)
            .put("direction", guidance?.direction.orEmpty()).put("distanceMeters", guidance?.distance ?: 0)
            .put("road", guidance?.road.orEmpty()).put("clearAttempt", clearAttempts)
            .put("maneuverIndex", guidance?.maneuverIndex ?: JSONObject.NULL)
            .put("guideMode", guidance?.guideMode ?: "INVALID")
            .put("strategy", policy.activeConfig.strategy.name).put("policyReason", policy.reason)
            .put("thresholdMeters", policy.thresholdMeters)
            .put("estimatedSeconds", policy.etaSeconds ?: JSONObject.NULL)
        PioneerHudCapture.event("meter_attempt", attempt)
        try {
            val ctl = controllerType
            val newInfo = guidance == null || retainedInfo == null
            val info = if (guidance == null) infoType.getConstructor().newInstance()
                else retainedInfo ?: infoType.getConstructor().newInstance()
            fun number(setter: String, constant: String) {
                infoType.getMethod(setter, Int::class.javaPrimitiveType)
                    .invoke(info, ctl.getField(constant).getInt(null))
            }
            val meterType = mapOwnership?.meterType() ?: 1
            infoType.getMethod("setNaviInfoType", Int::class.javaPrimitiveType).invoke(info, meterType)
            if (newInfo) {
                number("setNaviState", if (guidance == null) "METER_NAVI_STATE_NOTINGUIDE" else "METER_NAVI_STATE_INGUIDE")
                number("setArriveEventType", "METER_ARRIVE_EVENT_TYPE_INVALID")
                number("setMapDrawingMode", if (guidance == null) "METER_MAP_DRAWING_MODE_INVALID" else "METER_MAP_DRAWING_MODE_HEADUP")
                infoType.getMethod("setRoundAboutNum", Int::class.javaPrimitiveType).invoke(info, 0)
                infoType.getMethod("setVehicleDir", Float::class.javaPrimitiveType).invoke(info, 0f)
            }
            // A retained SDK object must also change mode when entering/leaving the approach.
            number("setGuideState", "METER_GUIDE_STATE_${guidance?.guideMode ?: "INVALID"}")
            val direction = guidance?.direction ?: "METER_GUIDE_DIRECTION_INVALID"
            val mappedDirection = if (meterType == 2) when (direction) {
                "METER_GUIDE_DIRECTION_LEFT_FRONT_AUTONAVI" -> "METER_GUIDE_DIRECTION_LEFT_FRONT_1"
                "METER_GUIDE_DIRECTION_RIGHT_FRONT_AUTONAVI" -> "METER_GUIDE_DIRECTION_RIGHT_FRONT_1"
                "METER_GUIDE_DIRECTION_DEST_AUTONAVI" -> "METER_GUIDE_DIRECTION_DEST_1"
                "METER_GUIDE_DIRECTION_ISLAND_IN_AUTONAVI", "METER_GUIDE_DIRECTION_ISLAND_OUT_AUTONAVI" -> "METER_GUIDE_DIRECTION_ISLAND_IN"
                else -> direction
            } else direction
            number("setGuideDir", mappedDirection)
            attempt.put("meterType", meterType).put("mappedDirection", mappedDirection)
            infoType.getMethod("setNextRdName", String::class.java).invoke(info, guidance?.road.orEmpty())
            infoType.getMethod("setNextRdDistance", Int::class.javaPrimitiveType).invoke(info, guidance?.distance ?: 0)
            if (guidance != null) { retainedInfo = info; ownsOutput = true; acceptsMeterRefresh = true }
            check(ctl.getMethod("setNaviInfo", infoType).invoke(ctl.getMethod("getInstance").invoke(null), info) == true) {
                "Meter rejected navigation"
            }
            ownsOutput = guidance != null
            relinquishedOutput = false
            last = guidance
            lastError = null
            if (guidance == null) { clearPending = false; retainedInfo = null }
            successes++
            PioneerHudCapture.event("meter_success", attempt)
        } catch (error: Exception) {
            val message = (error.cause ?: error).toString()
            if (message != lastError) Log.w("DiPlay-Pioneer-Navi", "Navigation output failed: $message")
            lastError = message
            failures++
            PioneerHudCapture.event("meter_failure", attempt.put("error", message))
        }
    }

    private fun publish() {
        val queue = worker.status()
        val displayed = last
        PioneerDebugRuntime.navigationHudState = JSONObject().put("applicable", applicable)
            .put("active", displayed != null).put("direction", displayed?.direction.orEmpty())
            .put("distanceMeters", displayed?.distance ?: 0).put("updatedAtMs", System.currentTimeMillis())
            .put("error", lastError ?: JSONObject.NULL).put("reason", reason)
            .put("clearPending", clearPending).put("clearAttempts", clearAttempts)
            .put("clearRetryExhausted", clearPending && clearAttempts >= 5)
            .put("outputMayRemain", ownsOutput || relinquishedOutput || clearPending)
            .put("outputRelinquished", relinquishedOutput).put("originalMap", mapOwnership?.snapshot() ?: JSONObject.NULL)
            .put("candidateAvailable", candidateAvailable).put("ownerPid", android.os.Process.myPid())
            .put("routeFrames", routeFrames.get()).put("maneuverFrames", maneuverFrames.get())
            .put("lastReceivedAtMs", receivedAtMs.get()).put("lastParsedAtMs", parsedAtMs)
            .put("appleType", lastType ?: JSONObject.NULL).put("invalidFrames", invalidFrames)
            .put("queueAccepting", queue.first).put("queueDepth", queue.second)
            .put("queueOverflows", worker.overflowCount).put("queueStopReason", worker.stopReason).put("sdkSuccesses", successes).put("sdkFailures", failures)
            .put("inputDistanceMeters", inputDistanceMeters ?: JSONObject.NULL)
            .put("suppressedUpdates", suppressedUpdates).put("distanceRefreshMinMs", 500)
            .put("meterListenerRegistered", meterListener != null).put("meterListenerError", listenerError ?: JSONObject.NULL)
            .put("meterRefreshRequests", meterRefreshRequests.get()).put("meterRefreshSends", meterRefreshSends)
            .put("meterRefreshPending", meterRefreshPending.get())
            .put("guideMode", displayed?.guideMode ?: "INVALID").put("distancePolicy", policy.activeConfig.strategy.name)
            .put("maneuverIndex", route.currentManeuverIndex() ?: JSONObject.NULL)
            .put("presentationPhase", if (policy.showing) "APPROACH" else "NORMAL")
            .put("presentationReason", policy.reason)
            .put("approachMaxMeters", policy.thresholdMeters)
            .put("hudEnabled", configured.enabled)
            .put("configuredStrategy", configured.strategy.name).put("activeStrategy", policy.activeConfig.strategy.name)
            .put("policySettingsPending", configured.copy(enabled = policy.activeConfig.enabled) != policy.activeConfig)
            .put("policyReason", policy.reason).put("policyThresholdMeters", policy.thresholdMeters)
            .put("estimatedApproachSpeedMps", policy.speedMps ?: JSONObject.NULL)
            .put("estimatedSecondsToManeuver", policy.etaSeconds ?: JSONObject.NULL)
            .put("estimateReason", policy.estimateReason)
            .put("distanceResolutionMeters", policy.distanceResolutionMeters)
            .put("displayVerified", false).toString()
        val now = System.currentTimeMillis()
        if (now - lastCheckpointMs >= 5_000) {
            lastCheckpointMs = now
            PioneerHudCapture.event("checkpoint", JSONObject(PioneerDebugRuntime.navigationHudState))
        }
    }

    /** Use the physically verified icon families; unknown maneuvers clear rather than guess. */
    private fun direction(type: Int, drivingSide: Int): String? {
        val code = BydManeuverCodes.gaode(type, drivingSide)
        val suffix = when (code) {
            BydManeuverCodes.LEFT, BydManeuverCodes.SHARP_LEFT -> "LEFT"
            BydManeuverCodes.RIGHT, BydManeuverCodes.SHARP_RIGHT -> "RIGHT"
            BydManeuverCodes.SLIGHT_LEFT -> "LEFT_FRONT_AUTONAVI"
            BydManeuverCodes.SLIGHT_RIGHT -> "RIGHT_FRONT_AUTONAVI"
            BydManeuverCodes.U_TURN_LEFT, BydManeuverCodes.U_TURN_RIGHT -> "BACK"
            BydManeuverCodes.STRAIGHT -> "FRONT"
            BydManeuverCodes.DESTINATION -> "DEST_AUTONAVI"
            BydManeuverCodes.ROUNDABOUT_ENTER -> "ISLAND_IN_AUTONAVI"
            BydManeuverCodes.ROUNDABOUT_EXIT -> "ISLAND_OUT_AUTONAVI"
            BydManeuverCodes.NONE -> return null
            else -> "ISLAND_IN_AUTONAVI" // Exit numbering deliberately not enabled.
        }
        return "METER_GUIDE_DIRECTION_$suffix"
    }
}

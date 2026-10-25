package com.shilapi.xcertplay.hud

import android.content.Context
import android.util.Log
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import com.shilapi.xcertplay.pioneer.PioneerWiredBackend
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** CarPlay route metadata -> the Pioneer Meter service, shared by HUD and instrument cluster. */
internal object PioneerNavigationBridge {
    private val route = BydHudRouteState()
    private val worker = NavigationOutputWorker("diplay-pioneer-navigation", ::clear)
    @Volatile private var applicable = false
    private var initialized = false
    private var ticking = false
    private var ownsOutput = false
    private var last: Guidance? = null
    private var lastError: String? = null
    private data class Guidance(val direction: String, val distance: Int, val road: String)
    private val controllerType by lazy { Class.forName("jp.pioneer.ceam.MeterService.MeterServiceController") }
    private val infoType by lazy { Class.forName("jp.pioneer.ceam.MeterService.MeterNaviInfo") }

    fun start(context: Context) {
        if (!PioneerWiredBackend.usesVehicleAdaptation(context)) return
        applicable = true
        worker.start {
            initialized = true
            // Also remove a stale display left by a previous process that could not run cleanup.
            send(null)
            if (!ticking) {
                ticking = true
                Executors.newSingleThreadScheduledExecutor { task ->
                    Thread(task, "diplay-pioneer-navigation-tick").apply { isDaemon = true }
                }.scheduleAtFixedRate({ worker.submit { refresh() } }, 1, 1, TimeUnit.SECONDS)
            }
        }
    }

    fun onFrame(frame: Iap2Frame) {
        if (!applicable) return
        if (frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_UPDATE &&
            frame.messageId != BydHudRouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE) return
        worker.submit { route.accept(frame.messageId, frame.payload); refresh() }
    }

    fun end() { if (applicable) worker.clear() }

    private fun refresh() {
        if (!initialized) return
        val apple = route.currentApple()
        val next = apple?.let {
            direction(it.type, it.drivingSide)?.let { icon ->
                Guidance(icon, it.distanceMeters.coerceIn(0, 1_000_000), it.road.take(128))
            }
        }
        if (next != last || (next == null && ownsOutput)) send(next)
    }

    private fun clear() {
        route.clear() // Never allow the periodic refresh to resurrect a disconnected route.
        if (initialized && ownsOutput) send(null)
        last = null
    }

    private fun send(guidance: Guidance?) {
        try {
            val ctl = controllerType
            val info = infoType.getConstructor().newInstance()
            fun number(setter: String, constant: String) {
                infoType.getMethod(setter, Int::class.javaPrimitiveType)
                    .invoke(info, ctl.getField(constant).getInt(null))
            }
            number("setNaviInfoType", "METER_NAVI_INFO_TYPE_AUTONAVI")
            number("setNaviState", if (guidance == null) "METER_NAVI_STATE_NOTINGUIDE" else "METER_NAVI_STATE_INGUIDE")
            number("setGuideState", if (guidance == null) "METER_GUIDE_STATE_INVALID" else "METER_GUIDE_STATE_TBT")
            number("setGuideDir", guidance?.direction ?: "METER_GUIDE_DIRECTION_INVALID")
            number("setArriveEventType", "METER_ARRIVE_EVENT_TYPE_INVALID")
            number("setMapDrawingMode", "METER_MAP_DRAWING_MODE_INVALID")
            infoType.getMethod("setRoundAboutNum", Int::class.javaPrimitiveType).invoke(info, 0)
            infoType.getMethod("setVehicleDir", Float::class.javaPrimitiveType).invoke(info, 0f)
            infoType.getMethod("setNextRdName", String::class.java).invoke(info, guidance?.road.orEmpty())
            infoType.getMethod("setNextRdDistance", Int::class.javaPrimitiveType).invoke(info, guidance?.distance ?: 0)
            if (guidance != null) ownsOutput = true
            check(ctl.getMethod("setNaviInfo", infoType).invoke(ctl.getMethod("getInstance").invoke(null), info) == true) {
                "Meter rejected navigation"
            }
            ownsOutput = guidance != null
            last = guidance
            lastError = null
            PioneerDebugRuntime.navigationHudState = JSONObject().put("active", guidance != null)
                .put("direction", guidance?.direction.orEmpty()).put("distanceMeters", guidance?.distance ?: 0)
                .put("updatedAtMs", System.currentTimeMillis()).toString()
            Log.i("DiPlay-Pioneer-Navi", if (guidance == null) "Navigation cleared" else
                "Navigation direction=${guidance.direction} meters=${guidance.distance}")
        } catch (error: Exception) {
            val message = (error.cause ?: error).toString()
            if (message != lastError) Log.w("DiPlay-Pioneer-Navi", "Navigation output failed: $message")
            lastError = message
            PioneerDebugRuntime.navigationHudState = JSONObject().put("error", message)
                .put("updatedAtMs", System.currentTimeMillis()).toString()
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
            BydManeuverCodes.NONE -> return null
            else -> "ISLAND_IN_AUTONAVI" // Exit numbering deliberately not enabled.
        }
        return "METER_GUIDE_DIRECTION_$suffix"
    }
}

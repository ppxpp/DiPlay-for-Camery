package com.shilapi.xcertplay.pioneer

import android.content.Context
import org.json.JSONObject

/** DiPlay owns navigation output while its worker renews the filter. OEM maps keep running. */
class PioneerHudMapOwnership(context: Context) {
    private val filter = PioneerHudFilter(context)
    private var selectedSource = -1
    private var sourceResult: Result<Int>? = null
    private val sourceController by lazy {
        Class.forName("jp.pioneer.ceam.widget.SettingServer.SettingServerController")
            .getMethod("getInstance").invoke(null)
    }
    private val sourceMethod by lazy { sourceController.javaClass.getMethod("getMapType") }
    private var held = false
    private var heldSource: Int? = null
    private var state = "waiting"
    private var error: String? = null

    /** Reset only for a new CarPlay session, never for a prompt ending or HUD toggle. */
    fun beginSession() {
        sourceResult = null
        selectedSource = -1
    }

    fun maintain(active: Boolean) { filter.maintain(active && runCatching { source() in 0..1 }.getOrDefault(false)) }

    fun acquire(): Boolean = try {
        val selected = source()
        check(selected in 0..1) { "Unsupported navigation source" }
        filter.maintain(true)
        held = filter.ready()
        heldSource = if (held) selected else null
        state = if (held) "exclusive_navigation_filter" else "waiting_for_navigation_filter"
        error = null
        held
    } catch (failure: Exception) {
        held = false; heldSource = null; state = "ownership_unconfirmed"; error = failure.toString(); false
    }

    fun mayClear(): Boolean = held && filter.ready()
    fun release() { filter.release(); held = false; heldSource = null; state = "released"; error = null }
    fun meterType(): Int = if ((heldSource ?: source()) == 0) 2 else 1
    private fun source(): Int {
        // Cache failures too: a broken vehicle service must not create a per-frame Binder retry loop.
        val result = sourceResult ?: runCatching {
            sourceMethod.invoke(sourceController) as Int
        }.also { sourceResult = it }
        return result.getOrThrow().also { selectedSource = it }
    }
    fun snapshot(): JSONObject {
        // Observability must not issue extra vehicle Binder calls on every route frame.
        val selected = selectedSource
        val filterState = filter.snapshot()
        return JSONObject().put("held", held && filterState.optBoolean("ready")).put("state", state)
            .put("error", error ?: JSONObject.NULL).put("mapType", selected)
            .put("meterType", if (selected == 0) 2 else 1).put("pausedPackage", JSONObject.NULL)
            .put("filter", filterState)
    }
}

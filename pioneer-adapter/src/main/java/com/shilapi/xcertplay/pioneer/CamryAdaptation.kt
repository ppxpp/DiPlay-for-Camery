package com.shilapi.xcertplay.pioneer

import android.content.Context
import org.json.JSONObject

/** One decision for all Camry A-navigation extensions, including privileged helper processes. */
object CamryAdaptation {
    enum class Mode { AUTO, ON, OFF }
    private var appContext: Context? = null
    @Volatile private var applied = Mode.AUTO
    fun mode(context: Context): Mode = runCatching {
        Mode.valueOf(context.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE)
            .getString("camry_adaptation", "AUTO") ?: "AUTO")
    }.getOrDefault(Mode.AUTO)
    fun initialize(context: Context) {
        appContext = context.applicationContext
        if (PioneerWiredSession.active) return
        val previous = applied
        applied = mode(context)
        if (applied != previous) ShortcutBarPolicy.adaptationChanged(context.applicationContext)
        PioneerUsbLaunchPolicy.restoreSystemLaunchIfDisabled(context)
    }
    fun save(context: Context, mode: Mode) {
        check(context.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE).edit()
            .putString("camry_adaptation", mode.name).commit()) { "Could not save Camry adaptation" }
        // Let an existing wired session retire under the policy with which it was created.
        if (!PioneerWiredSession.active) initialize(context)
    }
    fun enabled(): Boolean = resolve(applied, PioneerUsbPolicy.matchesModel())
    fun sessionEnded() { appContext?.let(::initialize) }
    fun requestMode(): String = applied.name
    fun useRequestMode(request: JSONObject) {
        applied = Mode.valueOf(request.optString("camryAdaptation", "AUTO"))
    }
    internal fun resolve(mode: Mode, modelMatches: Boolean): Boolean = when (mode) {
        Mode.AUTO -> modelMatches
        Mode.ON -> true
        Mode.OFF -> false
    }
}

package com.shilapi.xcertplay.pioneer

import android.content.Context

/** Manual choice only. A failed connection must never change this preference. */
object PioneerWiredBackend {
    enum class Mode { LWIP, LWIP_OPTIMIZED, VPN }
    val selectorVisible: Boolean get() = BuildConfig.DEBUG
    fun availableModes(): List<Mode> = Mode.entries
    fun requireAvailable(mode: Mode) {
        require(mode in availableModes()) { "Unsupported wired connection method" }
    }
    private fun prefs(context: Context) = context.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE)
    fun mode(context: Context): Mode {
        val value = prefs(context).getString("wired_backend", null)
        return availableModes().firstOrNull { it.name == value } ?: Mode.VPN
    }
    fun save(context: Context, mode: Mode) {
        requireAvailable(mode)
        check(prefs(context).edit().putString("wired_backend", mode.name).commit()) { "Could not save wired backend" }
    }
    fun explicitLwip(context: Context): Boolean = prefs(context).contains("wired_backend") && mode(context) in setOf(Mode.LWIP, Mode.LWIP_OPTIMIZED)
    fun optimizedEnabled(context: Context): Boolean = mode(context) == Mode.LWIP_OPTIMIZED
    /** Selection before a session exists; never inferred from a running IP stack. */
    fun usesVehicleAdaptation(context: Context): Boolean = PioneerUsbPolicy.matchesDevice() &&
        PioneerUsbPolicy.enabled(context)
}

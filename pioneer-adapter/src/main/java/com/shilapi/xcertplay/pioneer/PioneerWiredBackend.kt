package com.shilapi.xcertplay.pioneer

import android.content.Context

/** Manual choice only. A failed native connection must never change this preference. */
object PioneerWiredBackend {
    enum class Mode { LWIP, NATIVE_NCM, LWIP_OPTIMIZED }
    private fun prefs(context: Context) = context.getSharedPreferences("pioneer-adapter", Context.MODE_PRIVATE)
    fun mode(context: Context): Mode {
        val value = prefs(context).getString("wired_backend", null)
        return Mode.entries.firstOrNull { it.name == value }
            ?: if (prefs(context).getBoolean("native_ncm_enabled", false)) Mode.NATIVE_NCM else Mode.LWIP
    }
    fun save(context: Context, mode: Mode) {
        check(prefs(context).edit().putString("wired_backend", mode.name)
            .putBoolean("native_ncm_enabled", mode == Mode.NATIVE_NCM).commit()) { "Could not save wired backend" }
    }
    fun explicitLwip(context: Context): Boolean = prefs(context).contains("wired_backend") && mode(context) != Mode.NATIVE_NCM
    fun optimizedEnabled(context: Context): Boolean = mode(context) == Mode.LWIP_OPTIMIZED
    /** Selection before a session exists; never inferred from a running IP stack. */
    fun usesVehicleAdaptation(context: Context): Boolean = PioneerUsbPolicy.matchesDevice() &&
        (nativeEnabled(context) || PioneerLwipRuntime.enabled(context))

    fun nativeEnabled(context: Context): Boolean = mode(context) == Mode.NATIVE_NCM

    fun saveNativeEnabled(context: Context, enabled: Boolean) {
        save(context, if (enabled) Mode.NATIVE_NCM else Mode.LWIP)
    }
}

package com.shilapi.xcertplay.hud

import android.content.Context
import android.content.SharedPreferences

/** Shared by the home settings and in-session settings; new installs default to the adaptive lead time. */
object CamryHudSettings {
    val distances = listOf(50, 80, 100, 150, 200, 300, 400, 600, 800, 1000, 1200, 1500)
    private fun prefs(context: Context) = context.getSharedPreferences("camry_navigation_hud", Context.MODE_PRIVATE)
    fun preferences(context: Context): SharedPreferences = prefs(context)
    fun load(context: Context): CamryHudConfig {
        val p = prefs(context)
        fun distance(key: String, default: Int) = p.getInt(key, default).takeIf { it in distances } ?: default
        return CamryHudConfig(
            enabled = p.getBoolean("enabled", true),
            strategy = CamryHudStrategy.entries.firstOrNull { it.name == p.getString("strategy", null) }
                ?: CamryHudStrategy.ADAPTIVE,
            fixedMeters = distance("fixed", 400), turnMeters = distance("turn", 200),
            complexMeters = distance("complex", 1000), straightMeters = distance("straight", 100),
            advance = CamryHudAdvance.entries.firstOrNull { it.name == p.getString("advance", null) }
                ?: CamryHudAdvance.STANDARD,
        )
    }
    fun save(context: Context, config: CamryHudConfig) {
        require(listOf(config.fixedMeters, config.turnMeters, config.complexMeters, config.straightMeters).all { it in distances })
        prefs(context).edit().putBoolean("enabled", config.enabled).putString("strategy", config.strategy.name)
            .putInt("fixed", config.fixedMeters).putInt("turn", config.turnMeters)
            .putInt("complex", config.complexMeters).putInt("straight", config.straightMeters)
            .putString("advance", config.advance.name).apply()
    }
}

package com.shilapi.xcertplay.pioneer

import android.content.Context

/** Persisted vehicle PLAY/PAUSE bridge policy; reads are live so no CarPlay reconnect is needed. */
object PioneerMediaControlSettings {
    private const val PREFS = "pioneer-media-control"
    private const val ACCEPT_PLAYBACK = "accept_vehicle_play_pause"

    fun acceptsPlayback(context: Context): Boolean =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(ACCEPT_PLAYBACK, true)

    fun setAcceptsPlayback(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ACCEPT_PLAYBACK, enabled).apply()
    }
}

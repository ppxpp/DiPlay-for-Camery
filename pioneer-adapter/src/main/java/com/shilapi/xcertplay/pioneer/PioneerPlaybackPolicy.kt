package com.shilapi.xcertplay.pioneer

/** Playback defaults are independent of the optional debug service and its main-thread handler. */
object PioneerPlaybackPolicy {
    // Both wired backends need the same iPhone appearance compatibility on this head unit.
    // Wireless and other firmware retain the upstream appearance declarations.
    val appearanceDeclarationsEnabled: Boolean
        get() = !PioneerWiredSession.adaptationActive

    // Read on each media write so a diagnostic override can still change a running session.
    @Volatile var audioClockTrimOverride: Boolean? = null
        private set
    var audioClockTrimEnabled: Boolean
        get() = audioClockTrimOverride ?: !PioneerWiredSession.adaptationActive
        set(value) { audioClockTrimOverride = value }
}

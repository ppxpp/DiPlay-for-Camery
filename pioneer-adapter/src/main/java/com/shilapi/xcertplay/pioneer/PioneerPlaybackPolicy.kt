package com.shilapi.xcertplay.pioneer

/** Playback defaults are independent of the optional debug service and its main-thread handler. */
object PioneerPlaybackPolicy {
    // Both wired backends need the same iPhone appearance compatibility on this head unit.
    // Wireless and other firmware retain the upstream appearance declarations.
    val appearanceDeclarationsEnabled: Boolean
        get() = !PioneerWiredSession.adaptationActive

    @Volatile private var freescaleNoReorderOverride: Boolean? = null
    // Decoder configuration additionally gates this to the verified firmware, main AVC stream
    // and OMX.Freescale codec. Keep diagnostics optional for the production default.
    var freescaleNoReorderEnabled: Boolean
        get() = freescaleNoReorderOverride ?: (PioneerWiredSession.current?.backend == PioneerWiredSession.Backend.VPN)
        set(value) { freescaleNoReorderOverride = value }

    // Read on each media write so a diagnostic override can still change a running session.
    @Volatile var audioClockTrimOverride: Boolean? = null
        private set
    var audioClockTrimEnabled: Boolean
        get() = audioClockTrimOverride ?: !PioneerWiredSession.adaptationActive
        set(value) { audioClockTrimOverride = value }
}

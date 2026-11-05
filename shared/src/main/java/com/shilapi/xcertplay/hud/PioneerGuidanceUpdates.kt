package com.shilapi.xcertplay.hud

/** Coalesce interleaved route/detail frames without suppressing normal distance progress. */
internal class PioneerGuidanceUpdates {
    data class Guidance(val maneuverIndex: Int, val direction: String, val distance: Int, val road: String,
        val guideMode: String = "TBT")
    private var pendingIdentity: Triple<Int, String, String>? = null
    private var pendingSinceNs = 0L
    private var lastAttemptNs: Long? = null

    fun reset() { pendingIdentity = null; pendingSinceNs = 0L; lastAttemptNs = null }

    fun shouldSend(previous: Guidance?, next: Guidance, nowNs: Long): Boolean {
        val identity = Triple(next.maneuverIndex, next.direction, next.road)
        val previousIdentity = previous?.let { Triple(it.maneuverIndex, it.direction, it.road) }
        if (identity != previousIdentity) {
            if (pendingIdentity != identity) {
                pendingIdentity = identity
                pendingSinceNs = nowNs
            }
            // 0x5201 often changes the distance before 0x5202 supplies the new arrow/road.
            // Do not reset the dwell time for every metre of distance progress.
            if (nowNs - pendingSinceNs < 500_000_000L) return false
            return lastAttemptNs?.let { nowNs - it >= 500_000_000L } ?: true
        }
        pendingIdentity = null
        val old = previous ?: return false
        if (next.guideMode != old.guideMode) return canRefresh(nowNs)
        if (next.distance == old.distance) return false
        return lastAttemptNs?.let { nowNs - it >= 500_000_000L } ?: true
    }

    fun attempted(nowNs: Long) { lastAttemptNs = nowNs }

    fun canRefresh(nowNs: Long): Boolean = lastAttemptNs?.let { nowNs - it >= 500_000_000L } ?: true

}

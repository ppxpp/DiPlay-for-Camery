package com.shilapi.xcertplay.hud

import java.util.ArrayDeque
import kotlin.math.abs

/** User choices are independent of the OEM road/cross-image metadata. */
enum class CamryHudStrategy { FIXED_DISTANCE, MANEUVER_DISTANCE, ADAPTIVE }
enum class CamryHudAdvance(
    val turnSeconds: Int, val turnFloorMeters: Int,
    val complexSeconds: Int, val complexFloorMeters: Int,
    val straightSeconds: Int, val straightFloorMeters: Int,
) {
    LATER(20, 200, 35, 400, 10, 100),
    STANDARD(26, 250, 45, 500, 13, 120),
    EARLIER(32, 300, 55, 600, 16, 150);

    companion object {
        const val TURN_MAX_METERS = 800
        const val COMPLEX_MAX_METERS = 1500
        const val STRAIGHT_MAX_METERS = 300
    }
}
data class CamryHudConfig(
    val enabled: Boolean = true,
    val strategy: CamryHudStrategy = CamryHudStrategy.ADAPTIVE,
    val fixedMeters: Int = 400,
    val turnMeters: Int = 200,
    val complexMeters: Int = 1000,
    val straightMeters: Int = 100,
    val advance: CamryHudAdvance = CamryHudAdvance.STANDARD,
)

/** Runs only on the navigation worker. Samples are actual distance TLVs, never maintenance ticks. */
internal class CamryHudPolicy {
    data class Input(val index: Int, val type: Int, val road: String, val distance: Int)
    enum class Kind { TURN, COMPLEX, STRAIGHT }
    private data class Sample(val ns: Long, val distance: Int)
    private val samples = ArrayDeque<Sample>()
    private var input: Input? = null
    private var confirmationNs: Long? = null
    private var stopped = false
    private var stopDistance: Int? = null
    private var lastObservationNs: Long? = null
    private var lastChangeNs: Long? = null
    var distanceResolutionMeters = 1; private set
    var activeConfig = CamryHudConfig(); private set
    var showing = false; private set
    var speedMps: Double? = null; private set
    var etaSeconds: Double? = null; private set
    var thresholdMeters = 400; private set
    var reason = "no_guidance"; private set
    var estimateReason = "collecting"; private set

    fun reset() {
        input = null
        showing = false
        resetEstimate()
        reason = "no_guidance"
    }

    private fun resetEstimate() {
        samples.clear()
        speedMps = null
        etaSeconds = null
        confirmationNs = null
        stopped = false
        stopDistance = null
        lastObservationNs = null
        lastChangeNs = null
        distanceResolutionMeters = 1
        estimateReason = "collecting"
    }

    private fun select(next: Input, configured: CamryHudConfig) {
        val old = input
        if (old == null || old.index != next.index || old.type != next.type || old.road != next.road) {
            // Number changes can describe the same physical instruction. Never mix their speed samples.
            val continuous = old != null && old.type == next.type &&
                abs(old.distance - next.distance) <= maxOf(30, distanceResolutionMeters)
            showing = showing && continuous && configured == activeConfig
            activeConfig = configured
            confirmationNs = null
            if (!continuous) resetEstimate()
        } else if (next.distance - old.distance > maxOf(50, old.distance / 4)) {
            // Check against the last observed instruction before throttling speed samples. This also
            // handles the first distance update after re-enable, when no speed history exists yet.
            showing = false
            resetEstimate()
            estimateReason = "distance_discontinuity"
        }
        input = next
    }

    fun sample(next: Input, configured: CamryHudConfig, nowNs: Long) {
        select(next, configured)
        val previous = samples.peekLast()
        val observedGap = lastObservationNs?.let { (nowNs - it) / 1e9 }
        lastObservationNs = nowNs
        if (previous != null && nowNs - previous.ns < 500_000_000L) return
        val decrease = previous?.let { it.distance - next.distance } ?: 0
        if (observedGap != null && observedGap > 3.5) {
            resetEstimate()
            lastObservationNs = nowNs
            estimateReason = "missing_observations"
        } else if (previous != null) {
            val seconds = (nowNs - previous.ns) / 1e9
            if (decrease < -10 || decrease > maxOf(300.0, seconds * 70.0 + distanceResolutionMeters * 1.5)) {
                resetEstimate()
                lastObservationNs = nowNs
                estimateReason = "distance_discontinuity"
            } else if (stopped && (stopDistance ?: previous.distance) - next.distance >= 5) {
                // Start from the last stationary observation, not from the beginning of the wait.
                samples.clear()
                samples.addLast(previous)
                speedMps = null
                etaSeconds = null
                confirmationNs = null
                stopped = false
            }
        }
        if (decrease > 0 && previous != null) {
            distanceResolutionMeters = when {
                decrease % 100 == 0 && next.distance % 100 == 0 && previous.distance % 100 == 0 -> 100
                decrease % 10 == 0 && next.distance % 10 == 0 && previous.distance % 10 == 0 -> 10
                else -> 1
            }
            lastChangeNs = nowNs
        }
        if (lastChangeNs == null) lastChangeNs = nowNs
        samples.addLast(Sample(nowNs, next.distance))
        while (samples.isNotEmpty() && nowNs - samples.first.ns > 20_000_000_000L) samples.removeFirst()
        // Coarse CarPlay distances can stay unchanged for 3-5 seconds even at highway speed.
        val stopSeconds = (2.5 * distanceResolutionMeters / (speedMps ?: 10.0)).coerceIn(3.0, 30.0)
        if ((nowNs - lastChangeNs!!) / 1e9 >= stopSeconds) {
            if (!stopped) stopDistance = next.distance
            stopped = true
            speedMps = null
            etaSeconds = null
            confirmationNs = null
            estimateReason = "stopped_or_creeping"
            return
        }
        if (stopped) return
        val speeds = mutableListOf<Double>()
        if (distanceResolutionMeters >= 10) {
            val changes = mutableListOf<Sample>()
            for (sample in samples) if (changes.lastOrNull()?.distance != sample.distance) changes += sample
            // The first change may be a partial quantization interval. Use complete edge intervals.
            val complete = changes.drop(1)
            for ((start, end) in complete.zipWithNext()) {
                val seconds = (end.ns - start.ns) / 1e9
                val meters = start.distance - end.distance
                if (seconds in 0.5..30.0 && meters > 0) speeds += meters / seconds
            }
            if (speeds.size < 2 || complete.last().ns - complete.first().ns < 3_000_000_000L) return
        } else {
            val values = samples.filter { nowNs - it.ns <= 8_000_000_000L }
            if (values.size < 4 || nowNs - values.first().ns < 3_000_000_000L) return
            for (i in values.indices) for (j in i + 1 until values.size) {
                val seconds = (values[j].ns - values[i].ns) / 1e9
                val delta = values[i].distance - values[j].distance
                if (seconds in 2.0..5.0 && delta >= 0) speeds += delta / seconds
            }
            if (speeds.size < 3) return
        }
        val median = speeds.sorted()[speeds.size / 2]
        val deviation = speeds.map { abs(it - median) }.sorted()[speeds.size / 2]
        if (median < 1.5 || median > 70 || deviation > maxOf(1.5, median * 0.5)) {
            speedMps = null
            etaSeconds = null
            confirmationNs = null
            estimateReason = "low_confidence"
            return
        }
        speedMps = speedMps?.let { old -> old + (median - old) * if (median > old) 0.5 else 0.25 } ?: median
        estimateReason = "reliable"
    }

    fun evaluate(next: Input, configured: CamryHudConfig, nowNs: Long): Boolean {
        select(next, configured)
        if (!configured.enabled) { reset(); reason = "disabled"; return false }
        val config = activeConfig
        val kind = kind(next.type)
        if (lastObservationNs?.let { nowNs - it > 3_000_000_000L } != false) {
            speedMps = null
            etaSeconds = null
            confirmationNs = null
            estimateReason = "no_fresh_samples"
        }
        etaSeconds = speedMps?.let { next.distance / it }
        val fallback: Int
        var timeReached = false
        when (config.strategy) {
            CamryHudStrategy.FIXED_DISTANCE -> {
                fallback = config.fixedMeters
                thresholdMeters = fallback
            }
            CamryHudStrategy.MANEUVER_DISTANCE -> {
                fallback = when (kind) {
                    Kind.TURN -> config.turnMeters
                    Kind.COMPLEX -> config.complexMeters
                    Kind.STRAIGHT -> config.straightMeters
                }
                thresholdMeters = fallback
            }
            CamryHudStrategy.ADAPTIVE -> {
                val seconds: Int
                val maximum: Int
                val advance = config.advance
                when (kind) {
                    Kind.TURN -> { fallback = advance.turnFloorMeters; seconds = advance.turnSeconds; maximum = CamryHudAdvance.TURN_MAX_METERS }
                    Kind.COMPLEX -> { fallback = advance.complexFloorMeters; seconds = advance.complexSeconds; maximum = CamryHudAdvance.COMPLEX_MAX_METERS }
                    Kind.STRAIGHT -> { fallback = advance.straightFloorMeters; seconds = advance.straightSeconds; maximum = CamryHudAdvance.STRAIGHT_MAX_METERS }
                }
                val lead = seconds.toDouble()
                thresholdMeters = ((speedMps ?: 0.0) * lead).toInt().coerceIn(fallback, maximum)
                timeReached = etaSeconds?.let { it <= lead && next.distance <= maximum } == true
            }
        }
        if (showing) { reason = "held_current_guidance"; return true }
        if (next.distance <= fallback) {
            showing = true
            reason = "distance_trigger"
        } else if (timeReached) {
            val since = confirmationNs ?: nowNs.also { confirmationNs = it }
            if (nowNs - since >= 1_000_000_000L) { showing = true; reason = "time_trigger" }
            else reason = "confirming_time_trigger"
        } else {
            confirmationNs = null
            reason = "waiting_for_approach"
        }
        return showing
    }

    companion object {
        fun kind(type: Int): Kind = when (type) {
            // Existing Apple maneuver families: U-turn, roundabout and fork/ramp/lane directions.
            4, 6, 7, 13, 14, 18, 19, 20, 21, 22, 23, 26, in 28..46, 49, 50, 52, 53 -> Kind.COMPLEX
            3, 5, 8, 9, 11, 51 -> Kind.STRAIGHT
            else -> Kind.TURN
        }
    }
}

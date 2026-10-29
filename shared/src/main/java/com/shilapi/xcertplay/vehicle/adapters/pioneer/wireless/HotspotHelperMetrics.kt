package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

/** Fixed machine records in the helper response, independent of diagnostic/translated log prose. */
internal object HotspotHelperMetrics {
    private val phases = setOf("request_files", "dispatch_to_entry", "prewarm_init", "prewarm_authorization_wait",
        "operation_lock_wait", "policy_prepare", "local_firewall_prepare", "oem_ap_start", "frequency",
        "local_route_firewall", "policy_ready")
    private val pattern = Regex("DIPLAY_METRIC phase=([a-z_]+) durationMs=([0-9]+) result=(complete|failed)")
    data class Metric(val phase: String, val millis: Long, val result: String)
    fun parse(response: String): List<Metric> = response.lineSequence().mapNotNull { line ->
        val match = pattern.matchEntire(line) ?: return@mapNotNull null
        if (match.groupValues[1] !in phases) return@mapNotNull null
        val elapsed = match.groupValues[2].toLongOrNull()?.takeIf { it in 0..86_400_000L } ?: return@mapNotNull null
        Metric(match.groupValues[1], elapsed, match.groupValues[3])
    }.toList()
}

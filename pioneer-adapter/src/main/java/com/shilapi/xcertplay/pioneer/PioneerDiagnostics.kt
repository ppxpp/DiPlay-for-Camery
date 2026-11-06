package com.shilapi.xcertplay.pioneer

/** Aggregated by the lwIP owner, never writes files on media/USB threads. */
object PioneerDiagnostics {
    // Detailed per-buffer timing is opt-in through the debug-build diagnostics service.
    @Volatile var enabled = false
    private fun isCurrent(session: Long): Boolean =
        (PioneerLwipRuntime.active && session == PioneerLwipRuntime.sessionId) ||
            (PioneerWiredSession.current != null && session == PioneerWiredSession.sessionId)

    private val queues = LinkedHashMap<String, String>()
    @Synchronized fun queue(session: Long, name: String, depth: Int, bytes: Long, oldestNs: Long, peak: Int) {
        if (!enabled || !isCurrent(session) || queues.size >= 16 && !queues.containsKey(name)) return
        val now = System.nanoTime()
        queues[name] = "queue=$name depth=$depth bytes=$bytes oldestUs=${if (oldestNs == 0L) 0 else (now-oldestNs).coerceAtLeast(0)/1000} sampledPeakDepth=$peak atNs=$now"
    }
    @Synchronized fun queues(): List<String> = queues.values.toList()
    private val slowEvents = java.util.ArrayDeque<String>()
    private var lastSlowNs = 0L
    @Synchronized fun slow(session: Long, detail: String) {
        if (!enabled || !isCurrent(session)) return
        val now = System.nanoTime()
        if (now-lastSlowNs < 1_000_000_000L) return
        lastSlowNs = now
        if (slowEvents.size >= 16) slowEvents.removeFirst()
        slowEvents.addLast("LWIP_SLOW atNs=$now $detail")
    }
    @Synchronized fun drainSlow(): List<String> = slowEvents.toList().also { slowEvents.clear() }
    // A clear publishes a fresh generation: in-flight writers can only touch detached metrics.
    private class Metrics {
        val values = java.util.concurrent.ConcurrentHashMap<String, PioneerTiming>()
        fun metric(name: String): PioneerTiming? {
            values[name]?.let { return it }
            return synchronized(this) {
                values[name] ?: if (values.size < 64) PioneerTiming(name).also { values[name] = it } else null
            }
        }
    }
    @Volatile private var metrics = Metrics()
    fun record(session: Long, name: String, nanos: Long, failed: Boolean = false) {
        if (!enabled) return
        val generation = metrics
        if (!isCurrent(session)) return
        val metric = generation.metric(name) ?: return
        // Recheck after admission, which may have waited behind another new metric.
        if (generation === metrics && isCurrent(session)) metric.record(nanos, failed)
    }
    fun drain(): List<String> {
        val generation = metrics
        val result = generation.values.values.map { it.drain() }
        return if (generation === metrics) result else emptyList()
    }
    @Synchronized fun clear() { metrics = Metrics(); queues.clear(); slowEvents.clear(); lastSlowNs = 0 }
}

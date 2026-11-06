package com.shilapi.xcertplay.pioneer

/** Bounded, mergeable histogram. Nanoseconds internally; bucket upper bounds in microseconds. */
class PioneerTiming(private val name: String) {
    private val buckets = LongArray(128)
    private var count = 0L
    private var maxNs = 0L
    private var errors = 0L
    fun record(nanos: Long, failed: Boolean = false) {
        if (!PioneerDiagnostics.enabled) return
        synchronized(this) { recordEnabled(nanos, failed) }
    }
    private fun recordEnabled(nanos: Long, failed: Boolean) {
        val us = ((nanos.coerceAtLeast(0) + 999) / 1_000).coerceAtLeast(1)
        val exponent = 63 - java.lang.Long.numberOfLeadingZeros(us)
        val base = 1L shl exponent
        val slot = (exponent * 4 + ((us - base) * 4 + base - 1) / base).toInt().coerceAtMost(buckets.lastIndex)
        buckets[slot]++
        count++
        maxNs = maxOf(maxNs, nanos)
        if (failed) errors++
    }
    fun drain(): String {
        val snapshot: LongArray
        val count: Long
        val maxNs: Long
        val errors: Long
        synchronized(this) {
            snapshot = buckets.copyOf()
            count = this.count
            maxNs = this.maxNs
            errors = this.errors
            buckets.fill(0); this.count = 0; this.maxNs = 0; this.errors = 0
        }
        fun percentile(p: Int): Long {
            if (count == 0L) return 0
            val target = (count * p + 99) / 100
            var sum = 0L
            for (i in snapshot.indices) { sum += snapshot[i]; if (sum >= target) return limitUs(i) }
            return maxNs / 1_000
        }
        val result = "$name n=$count p50Us=${percentile(50)} p95Us=${percentile(95)} p99Us=${percentile(99)} maxUs=${maxNs / 1_000} errors=$errors"
        return result
    }
    private fun limitUs(slot: Int): Long = (1L shl (slot / 4)) * (4 + slot % 4) / 4
}

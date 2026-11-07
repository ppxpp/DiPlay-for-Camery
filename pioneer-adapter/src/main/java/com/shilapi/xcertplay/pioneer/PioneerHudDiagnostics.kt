package com.shilapi.xcertplay.pioneer

import android.os.SystemClock
import org.json.JSONObject

/** Always-on metadata only. No route names, coordinates or raw iAP payloads. No disk/Binder work. */
object PioneerHudDiagnostics {
    private val history = HudDiagnosticHistory()
    fun checkpoint(snapshot: JSONObject) = history.add(snapshot, SystemClock.elapsedRealtime(), System.currentTimeMillis())
    fun recent(): String = history.export(SystemClock.elapsedRealtime())
}

internal class HudDiagnosticHistory {
    private data class Entry(val elapsed: Long, val line: String)
    private val entries = ArrayDeque<Entry>()
    private var bytes = 0
    private var droppedEntries = 0L
    private var signature = ""
    private var lastSample = Long.MIN_VALUE

    @Synchronized fun add(state: JSONObject, elapsed: Long, wallTime: Long) {
        val filter = state.optJSONObject("originalMap")?.optJSONObject("filter")
        val next = listOf(state.opt("reason"), state.opt("hudEnabled"), state.opt("error"),
            state.opt("active"), state.opt("policyReason"), filter?.opt("error"), filter?.opt("ready")).toString()
        prune(elapsed)
        if (next == signature && lastSample != Long.MIN_VALUE && elapsed - lastSample < 1000) return
        signature = next; lastSample = elapsed
        val line = JSONObject().put("timeMs", wallTime).put("elapsedMs", elapsed)
            .put("snapshot", JSONObject(state.toString())).toString()
        val size = line.toByteArray(Charsets.UTF_8).size
        if (size > MAX_BYTES) { droppedEntries++; return }
        entries.addLast(Entry(elapsed, line)); bytes += size
        while (entries.size > 256 || bytes > MAX_BYTES) { droppedEntries++; removeFirst() }
    }
    @Synchronized fun export(elapsed: Long): String {
        prune(elapsed)
        val header = JSONObject().put("schema", 1).put("scope", "current_process")
            .put("retentionMs", 120_000).put("entries", entries.size).put("droppedEntries", droppedEntries)
            .put("maxBytes", MAX_BYTES).put("maxEntries", 256)
            .put("oldestElapsedMs", entries.firstOrNull()?.elapsed ?: JSONObject.NULL)
            .put("newestElapsedMs", entries.lastOrNull()?.elapsed ?: JSONObject.NULL)
        return header.toString() + "\n" + entries.joinToString("\n") { it.line }
    }
    private fun prune(now: Long) { while (entries.isNotEmpty() && now - entries.first().elapsed > 120_000) removeFirst() }
    private fun removeFirst() { bytes -= entries.removeFirst().line.toByteArray(Charsets.UTF_8).size }
    companion object { private const val MAX_BYTES = 512 * 1024 }
}

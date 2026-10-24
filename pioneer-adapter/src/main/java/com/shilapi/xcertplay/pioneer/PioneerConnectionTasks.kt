package com.shilapi.xcertplay.pioneer

/** Typed transport events, replayable for UI created after startup; never parse log strings. */
object PioneerConnectionTasks {
    data class Event(val id: String, val state: String, val detail: String, val atMillis: Long, val startedAtMillis: Long)
    private val events = linkedMapOf<String, Event>()
    @Synchronized fun beginAttempt() {
        val root = events["root"]
        events.clear()
        if (root != null) events["root"] = root
    }
    @Synchronized fun once(id: String, state: String) {
        val previous = events[id]?.state
        if (previous != state && !(previous == "COMPLETE" && state == "RUNNING")) update(id,state)
    }
    @Synchronized fun restart(id: String) { events.remove(id) }
    @Synchronized fun reset() { events.clear() }
    @Synchronized fun update(id: String, state: String, detail: String = "") {
        if (events[id]?.let { it.state == state && it.detail == detail } == true) return
        val now = System.nanoTime() / 1_000_000
        events[id] = Event(id, state, detail, now, events[id]?.startedAtMillis ?: now)
    }
    @Synchronized fun snapshot(): List<Event> = events.values.toList()
}

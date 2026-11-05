package com.shilapi.xcertplay.hud

import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/** Each output owns a worker: a blocked vendor service cannot block the phone or the other output. */
internal class NavigationOutputWorker(name: String, private val clearOutput: () -> Unit) {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
        ArrayBlockingQueue<Runnable>(128), { task -> Thread(task, name).apply { isDaemon = true } })
    private class Maintenance(private val action: () -> Unit) : Runnable {
        override fun run() { runCatching(action) }
    }
    private var accepting = false
    @Volatile var stopReason = "not_started"
        private set
    @Volatile var overflowCount = 0L
        private set
    @Synchronized fun status(): Pair<Boolean, Int> = accepting to executor.queue.size

    /** Cleanup/recovery must remain possible after frame delivery has stopped. Never blocks callers. */
    @Synchronized fun maintenance(action: () -> Unit): Boolean = try {
        if (executor.queue.size >= 120) false // Reserve room for lifecycle and cleanup tasks.
        else { executor.execute(Maintenance(action)); true }
    } catch (_: java.util.concurrent.RejectedExecutionException) { false }

    /** Frame backlog may be discarded; maintenance must survive session replacement. */
    private fun resetQueue(first: () -> Unit) {
        val retained = executor.queue.filterIsInstance<Maintenance>()
        executor.queue.clear()
        executor.execute { runCatching(first) }
        retained.forEach { executor.execute(it) }
    }

    @Synchronized fun start(initialize: () -> Unit) {
        if (accepting) return
        accepting = true
        stopReason = ""
        resetQueue { clearOutput(); initialize() }
    }

    @Synchronized fun submit(action: () -> Unit) {
        if (!accepting) return
        try {
            executor.execute { runCatching(action) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            overflowCount++
            // Incremental protocol frames cannot safely be dropped individually. Stop this output
            // until the next session, and clear after any in-flight vendor call returns.
            clear("queue_overflow")
        }
    }

    @Synchronized fun clear(reason: String = "session_end") {
        accepting = false
        stopReason = reason
        resetQueue(clearOutput)
    }
}

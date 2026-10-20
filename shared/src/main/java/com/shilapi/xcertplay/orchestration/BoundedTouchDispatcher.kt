package com.shilapi.xcertplay.orchestration

import com.shilapi.xcertplay.airplay.AirPlayContact
import java.io.Closeable
import java.util.ArrayDeque

/** b819 touch delivery: one scheduled drain, bounded pending reports, adjacent MOVE replacement. */
internal class BoundedTouchDispatcher<S>(
    private val execute: (Runnable) -> Unit,
    private val isCurrent: (S, Any) -> Boolean,
    private val send: (S, List<AirPlayContact>) -> Boolean,
    private val onFailure: (S, Any, String) -> Unit,
    private val diagnostic: (String) -> Unit = {},
    private val capacity: Int = 64,
    private val nowNs: () -> Long = System::nanoTime,
) : Closeable {
    private data class Pending<S>(val session: S, val token: Any, val contacts: List<AirPlayContact>, val move: Boolean, val at: Long)
    private val lock = Any()
    private val pending = ArrayDeque<Pending<S>>()
    private var scheduled = false
    private var closed = false
    private var failedSession: S? = null
    private var failedToken: Any? = null
    private var accepted = 0; private var sent = 0; private var merged = 0; private var stale = 0; private var failed = 0
    private var peak = 0; private var waitMax = 0L; private var sendMax = 0L; private var window = nowNs()
    init { require(capacity > 0) }

    fun offer(session: S, token: Any, contacts: List<AirPlayContact>, coalescible: Boolean): Boolean {
        val report = Pending(session, token, contacts.toList(), coalescible && contacts.isNotEmpty() && contacts.all { it.down }, nowNs())
        var launch = false; var overflow = false
        synchronized(lock) {
            if (closed || !isCurrent(session, token) || failedSession === session && failedToken === token) return false
            val iterator = pending.iterator()
            while (iterator.hasNext()) {
                val old = iterator.next()
                if (old.session !== session || old.token !== token) { iterator.remove(); stale++ }
            }
            val tail = pending.peekLast()
            if (report.move && tail?.move == true && tail.session === session && tail.token === token &&
                tail.contacts.map { it.id to it.down } == report.contacts.map { it.id to it.down }) {
                pending.removeLast(); merged++
            } else if (pending.size >= capacity) {
                markFailed(session, token); overflow = true
            }
            if (!overflow) {
                pending.addLast(report); accepted++; peak = maxOf(peak, pending.size)
                if (!scheduled) { scheduled = true; launch = true }
            }
        }
        if (overflow) { fail(session, token, "QUEUE_FULL"); return false }
        return !launch || schedule()
    }

    private fun schedule(): Boolean = try { execute(Runnable(::drain)); true } catch (_: Exception) {
        val report = synchronized(lock) {
            scheduled = false
            pending.peekFirst()?.also { markFailed(it.session, it.token) }
        }
        report?.let { fail(it.session, it.token, "EXECUTOR_REJECTED") }
        false
    }

    private fun drain() {
        val report = synchronized(lock) {
            if (closed || pending.isEmpty()) { scheduled = false; null } else pending.removeFirst()
        } ?: return
        if (runCatching { isCurrent(report.session, report.token) }.getOrDefault(false)) {
            val start = nowNs()
            val success = runCatching { send(report.session, report.contacts) }.getOrDefault(false)
            val failure = synchronized(lock) {
                waitMax = maxOf(waitMax, start - report.at); sendMax = maxOf(sendMax, nowNs() - start)
                if (success) { sent++; false }
                else if (!closed && isCurrent(report.session, report.token) &&
                    !(failedSession === report.session && failedToken === report.token)) {
                    markFailed(report.session, report.token); true
                } else false
            }
            if (failure) fail(report.session, report.token, "SEND_FAILED")
        } else synchronized(lock) { stale++ }
        reportIfDue()
        val again = synchronized(lock) {
            if (closed || pending.isEmpty()) { scheduled = false; false } else true
        }
        if (again) schedule()
    }

    private fun markFailed(session: S, token: Any) {
        failedSession = session; failedToken = token; failed++
        pending.removeAll { it.session === session && it.token === token }
    }
    private fun fail(session: S, token: Any, reason: String) {
        runCatching { diagnostic("touch_queue failure=$reason queued_report_sent=false") }
        runCatching { onFailure(session, token, reason) }
        reportIfDue(force = true)
    }
    private fun reportIfDue(force: Boolean = false) {
        val text = synchronized(lock) {
            val now = nowNs()
            if ((!force && now - window < 5_000_000_000L) || accepted + sent + stale + failed == 0) null else {
                val value = "touch_queue accepted=$accepted sent=$sent merged=$merged stale=$stale failed=$failed peak=$peak queued=${pending.size} waitMaxMs=${waitMax / 1_000_000} sendMaxMs=${sendMax / 1_000_000}"
                accepted = 0; sent = 0; merged = 0; stale = 0; failed = 0; peak = pending.size
                waitMax = 0; sendMax = 0; window = now
                value
            }
        }
        text?.let { runCatching { diagnostic(it) } }
    }
    override fun close() { synchronized(lock) { closed = true; stale += pending.size; pending.clear() }; reportIfDue(true) }
}

package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

/** One idle lease. All take/expiry/close transitions share the same monitor. */
internal class HotspotRetentionSlot<T>(private val now: () -> Long,
    private val schedule: (Long, () -> Unit) -> Unit, private val close: (T) -> Unit) {
    private class Idle<T>(val value: T, val stoppedAt: Long, var deadline: Long)
    @Volatile private var idle: Idle<T>?=null
    @Synchronized fun take(): T? {
        val entry=idle ?: return null
        idle=null
        if(now()>=entry.deadline) { close(entry.value);return null }
        return entry.value
    }
    @Synchronized fun retain(value: T, seconds: Int) {
        clear()
        if(seconds<=0) { close(value);return }
        val start=now()
        val entry=Idle(value,start,start+seconds.coerceAtMost(60)*1000L)
        idle=entry
        arm(entry)
    }
    @Synchronized fun reschedule(seconds: Int) {
        val entry=idle ?: return
        entry.deadline=entry.stoppedAt+seconds.coerceIn(0,60)*1000L
        expire(entry)
    }
    @Synchronized fun hasIdle() = idle!=null
    fun clearAction(): () -> Unit {
        val expected=idle
        return { synchronized(this) { if(idle===expected) clear() } }
    }
    @Synchronized fun clear() {
        val entry=idle ?: return
        idle=null
        close(entry.value)
    }
    private fun arm(entry: Idle<T>) { schedule((entry.deadline-now()).coerceAtLeast(0)) { expire(entry) } }
    @Synchronized private fun expire(entry: Idle<T>) {
        if(idle!==entry) return
        if(now()<entry.deadline) { arm(entry);return }
        idle=null
        close(entry.value)
    }
}

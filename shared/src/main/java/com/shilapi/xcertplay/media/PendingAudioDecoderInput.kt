package com.shilapi.xcertplay.media

/** Worker-confined single access unit; pressure cannot reset its deadline or grow a backlog. */
internal class PendingAudioDecoderInput(private val maxWaitNs: Long) {
    private class Packet(val payload: ByteArray, val ptsUs: Long, val sinceNs: Long)
    private var packet: Packet? = null
    val hasPending: Boolean get() = packet != null

    enum class Result { EMPTY, RETRY, ACCEPTED, EXPIRED, CANCELLED }

    fun offer(payload: ByteArray, ptsUs: Long, nowNs: Long) {
        check(packet == null) { "Audio decoder input is still pending" }
        packet = Packet(payload, ptsUs, nowNs)
    }

    /** Exactly one bounded attempt; the caller drains output and paces worker turns. */
    fun attempt(nowNs: Long, running: Boolean, queueInput: (ByteArray, Long) -> Boolean): Result {
        return attempt(nowNs, running, queueInput, null)
    }

    /** An expired packet may get one final nonblocking probe after output draining freed a slot. */
    fun attempt(nowNs: Long, running: Boolean, queueInput: (ByteArray, Long) -> Boolean,
                expiredProbe: ((ByteArray, Long) -> Boolean)?): Result {
        val current = packet ?: return Result.EMPTY
        val result = when {
            !running -> Result.CANCELLED
            nowNs - current.sinceNs >= maxWaitNs ->
                if (expiredProbe?.invoke(current.payload, current.ptsUs) == true) Result.ACCEPTED else Result.EXPIRED
            queueInput(current.payload, current.ptsUs) -> Result.ACCEPTED
            else -> Result.RETRY
        }
        if (result != Result.RETRY) packet = null
        return result
    }
}

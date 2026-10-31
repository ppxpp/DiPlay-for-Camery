package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.pioneer.PioneerWiredSession
import java.io.Closeable

/** The Pioneer vehicle microphone is gated separately from Android's RECORD_AUDIO permission. */
internal class PioneerMicrophoneLease private constructor(
    private val controller: Any,
    private val token: String,
    private val report: (String) -> Unit,
    private val settings: com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.Settings,
) : Closeable {
    fun currentToken(): String? = controller.javaClass.getMethod("getCurMicToken").invoke(controller) as? String
    fun ownsCurrentToken(): Boolean = currentToken() == token
    fun restoreOwnership(force: Boolean = false): Boolean = synchronized(PioneerMicrophoneLease::class.java) {
        if (closed) return@synchronized false
        val type = controller.javaClass
        val previous = currentToken()
        if (!force && previous == token) return@synchronized true
        com.shilapi.xcertplay.vehicle.adapters.pioneer.PioneerMediaSourceGate.withSelectedSource(controller, report) {
            val granted = type.getMethod("requestMicOn", String::class.java).invoke(controller, token) == true
            if (granted) Thread.sleep(settings.grantDelayMs.toLong())
            val restored = granted && currentToken() == token
            runCatching { report("Microphone: Pioneer ownership restore previous=$previous restored=$restored") }
            restored
        }
    }
    private var closed = false
    override fun close() = synchronized(PioneerMicrophoneLease::class.java) {
        if (closed) return@synchronized
        closed = true
        users--
        if (users > 0) return@synchronized
        runCatching {
            val type = controller.javaClass
            if (type.getMethod("getCurMicToken").invoke(controller) == token) {
                val released = type.getMethod("requestMicOff", String::class.java).invoke(controller, token)
                report("Microphone: Pioneer lease released=$released")
            }
        }.onFailure { runCatching { report("Microphone: Pioneer release failed=${it.javaClass.simpleName}") } }
    }

    companion object {
        private var users = 0
        fun acquire(token: String?, report: (String) -> Unit): PioneerMicrophoneLease? = synchronized(PioneerMicrophoneLease::class.java) {
            // The vehicle microphone gate belongs to the adaptation session, not its transport.
            // Vehicle microphone ownership also applies to transports without lwIP activity.
            if (!PioneerWiredSession.adaptationActive) return@synchronized null
            val settings = com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.current()
            val ownToken = if (settings.micIdentity == "WECHAT") "com.tencent.mm" else
                checkNotNull(token) { "Microphone application identity unavailable" }
            val type = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
            val controller = checkNotNull(type.getMethod("getInstance").invoke(null))
            var granted = false
            val grant = {
                granted = type.getMethod("requestMicOn", String::class.java).invoke(controller, ownToken) == true
                runCatching { report("Microphone: Pioneer lease granted=$granted testId=${settings.testId} order=${settings.micGrantOrder}") }
                check(granted) { "Vehicle microphone request refused" }
            }
            try {
                com.shilapi.xcertplay.vehicle.adapters.pioneer.PioneerMediaSourceGate.withSelectedSource(controller, report,
                    beforeSelection = if (settings.micGrantOrder == "MIC_FIRST") grant else null) {
                    if (settings.micGrantOrder == "SOURCE_FIRST") grant()
                    Thread.sleep(settings.grantDelayMs.toLong())
                    val owner = type.getMethod("getCurMicToken").invoke(controller)
                    runCatching { report("Microphone: Pioneer owner after source-ready=$owner") }
                    check(owner == ownToken) { "Vehicle microphone ownership changed before capture" }
                    users++
                    PioneerMicrophoneLease(controller, ownToken, report, settings)
                }
            } catch (error: Throwable) {
                runCatching {
                    if (granted && users == 0 && type.getMethod("getCurMicToken").invoke(controller) == ownToken)
                        type.getMethod("requestMicOff", String::class.java).invoke(controller, ownToken)
                }
                throw error
            }
        }
    }
}

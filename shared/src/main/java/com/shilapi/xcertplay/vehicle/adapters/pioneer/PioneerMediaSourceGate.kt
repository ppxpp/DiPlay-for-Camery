package com.shilapi.xcertplay.vehicle.adapters.pioneer

/** Serialize source changes with mic acquisition: selecting 300 can restore the assistant's mic. */
internal object PioneerMediaSourceGate {
    private val lock = Any()
    fun <T> withSelectedSource(controller: Any, report: (String) -> Unit, beforeSelection: (() -> Unit)? = null, action: () -> T): T = synchronized(lock) {
        beforeSelection?.invoke()
        val type = controller.javaClass
        fun source() = type.getMethod("getCurSource", Int::class.javaPrimitiveType).invoke(controller, 1) as Int
        val current = source()
        runCatching { report("Audio: Pioneer source check current=$current requiresMedia=${current != 300}") }
        val preservePhone = current == 302 && com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.current().callSourcePolicy == "PRESERVE_PHONE"
        if (preservePhone) runCatching { report("Audio: preserving Pioneer phone source=302 during microphone/focus operation") }
        if (current != 300 && !preservePhone) {
            val result = type.getMethod("change2Source", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                .invoke(controller, 1, 300)
            runCatching { report("Audio: Pioneer media source previous=$current requested=300 result=$result") }
            // CEAM source callbacks reset RecordToken asynchronously. Finish this transition
            // before asking for the microphone, matching the measured recording-test gate.
            Thread.sleep(com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.current().sourceDelayMs.toLong())
            check(source() == 300) { "Pioneer media source transition did not complete" }
        }
        action()
    }
}

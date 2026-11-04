package com.shilapi.xcertplay.feedback

import java.io.ByteArrayOutputStream

/** Best-effort, bounded logcat capture. A head unit may deny logcat; that is recorded, not fatal. */
internal object FeedbackLogcat {
    private const val LIMIT_BYTES = 512 * 1024
    private const val TIMEOUT_MILLIS = 2_500L

    data class Capture(val text: String?, val status: String, val reason: String? = null)

    fun capture(): Capture {
        val process = runCatching {
            ProcessBuilder(
                "/system/bin/logcat", "-d", "-t", "800", "-v", "threadtime",
                "*:S", "DiPlay:I", "AndroidRuntime:E", "ActivityManager:W",
            ).redirectErrorStream(true).start()
        }.getOrElse { return Capture(null, "unavailable", it.javaClass.simpleName) }

        val output = ByteArrayOutputStream()
        var truncated = false
        val reader = Thread({
            runCatching {
                process.inputStream.use { input ->
                    val buffer = ByteArray(16 * 1024)
                    var total = 0
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        val remaining = LIMIT_BYTES - total
                        if (remaining <= 0) {
                            truncated = true
                            break
                        }
                        val written = minOf(count, remaining)
                        output.write(buffer, 0, written)
                        total += written
                    }
                }
            }
        }, "diplay-feedback-logcat").apply { isDaemon = true; start() }

        reader.join(TIMEOUT_MILLIS)
        if (reader.isAlive) {
            runCatching { process.destroy() }
            reader.join(500)
            return Capture(null, "unavailable", "timeout")
        }
        val exit = runCatching { process.waitFor() }.getOrDefault(-1)
        val text = output.toString("UTF-8")
        if (text.isBlank()) return Capture(null, if (exit == 0) "empty" else "unavailable", "exit=$exit")
        return Capture(text, if (truncated) "truncated" else "success")
    }
}

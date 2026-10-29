package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.io.InterruptedIOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** Standalone read-only helpers have no children. Bound stdout AND process exit by one deadline. */
internal object BoundedProcessOutput {
    fun read(process: Process, timeoutMillis: Long, cancelled: () -> Boolean, limit: Int = 8192): String {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis)
        val reader = Executors.newSingleThreadExecutor { Thread(it, "pioneer-helper-read").apply { isDaemon = true } }
        val output = reader.submit<String> {
            process.inputStream.bufferedReader().use { input ->
                val buffer = CharArray(1024)
                val text = StringBuilder()
                while (true) {
                    val size = input.read(buffer)
                    if (size < 0) break
                    check(text.length + size <= limit) { "Vehicle helper output exceeded its limit" }
                    text.append(buffer, 0, size)
                }
                text.toString()
            }
        }
        return try {
            while (true) {
                if (cancelled()) throw InterruptedIOException("Vehicle helper cancelled")
                check(System.nanoTime() < deadline) { "Vehicle helper timed out" }
                val exit = try { process.exitValue() } catch (_: IllegalThreadStateException) { null }
                if (exit != null && output.isDone) {
                    check(exit == 0) { "Vehicle helper failed with exit $exit" }
                    return output.get()
                }
                // A closed stdout is not proof that the process exited.
                if (!output.isDone) try { output.get(20, TimeUnit.MILLISECONDS) } catch (_: TimeoutException) { }
                else Thread.sleep(20)
            }
            @Suppress("UNREACHABLE_CODE")
            error("unreachable")
        } finally {
            process.destroy()
            output.cancel(true)
            reader.shutdownNow()
        }
    }
}

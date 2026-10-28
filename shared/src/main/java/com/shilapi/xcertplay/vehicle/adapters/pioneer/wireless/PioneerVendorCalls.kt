package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/** A timed-out IPC may still mutate firmware. Never cancel its Future or queue another call behind it. */
internal class PioneerVendorCalls(private val timeoutMillis: Long = 3_000) {
    companion object {
        // SDK and raw Binder both operate on the same firmware state machine.
        val shared = PioneerVendorCalls()
    }
    private var pending: FutureTask<*>? = null

    @Synchronized
    fun <T> call(name: String, action: () -> T): T {
        check(pending?.isDone != false) { "Previous Pioneer Bluetooth call is still running; retry after service recovery" }
        val task = FutureTask(action)
        pending = task
        Thread(task, "pioneer-call-$name").apply { isDaemon = true; start() }
        return try {
            task.get(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (error: TimeoutException) {
            throw IllegalStateException("Pioneer Bluetooth $name timed out; waiting for service recovery", error)
        } catch (error: InterruptedException) {
            Thread.currentThread().interrupt()
            throw error
        } catch (error: ExecutionException) {
            throw (error.cause ?: error)
        } finally {
            // isDone is meaningful only because an in-flight Binder call is never cancelled.
            if (task.isDone) pending = null
        }
    }
}

package com.shilapi.xcertplay.orchestration

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** One attempt's worker. Cancel before closing its published transports; join outside state locks. */
internal class ParallelWirelessPreparation<T>(name: String, onFailure: (Throwable) -> Unit = {}, private val joinTimeoutMillis: Long = 35_000, action: () -> T) : Closeable {
    init { require(joinTimeoutMillis > 0) { "Wireless worker join timeout must be positive" } }
    private val failure = AtomicReference<Throwable?>()
    private val task = FutureTask {
        try {
            action()
        } catch (error: Throwable) {
            failure.set(error)
            try { onFailure(error) } catch (callbackError: Throwable) {
                if (callbackError !== error) error.addSuppressed(callbackError)
            }
            throw error
        }
    }
    private val worker = Thread(task, name).apply { isDaemon = true }
    fun start() = worker.start()
    fun failureOrNull(): Throwable? = failure.get()
    fun cancel() { task.cancel(true) }
    fun await(cancelled: () -> Boolean): T {
        while (true) {
            if (cancelled()) {
                failure.get()?.let { throw it }
                throw InterruptedIOException("Wireless preparation cancelled")
            }
            try { return task.get(100, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (error: ExecutionException) { throw (error.cause ?: error) }
        }
    }
    override fun close() {
        cancel()
        if (Thread.currentThread() !== worker) worker.join(joinTimeoutMillis)
        check(!worker.isAlive) { "Previous wireless preparation has not stopped" }
    }
}

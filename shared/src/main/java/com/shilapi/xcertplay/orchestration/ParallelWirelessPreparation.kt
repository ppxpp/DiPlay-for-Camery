package com.shilapi.xcertplay.orchestration

import java.io.Closeable
import java.io.InterruptedIOException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference

/** One attempt's worker. Cancel before closing its published transports; join outside state locks. */
internal class ParallelWirelessPreparation<T>(name: String, action: () -> T) : Closeable {
    private val failure = AtomicReference<Throwable?>()
    private val task = FutureTask {
        try {
            action()
        } catch (error: Throwable) {
            failure.set(error)
            throw error
        }
    }
    private val worker = Thread(task, name).apply { isDaemon = true }
    fun start() = worker.start()
    fun failureOrNull(): Throwable? = failure.get()
    fun cancel() { task.cancel(true) }
    fun await(cancelled: () -> Boolean): T {
        while (true) {
            if (cancelled()) throw InterruptedIOException("Wireless preparation cancelled")
            try { return task.get(100, TimeUnit.MILLISECONDS) }
            catch (_: TimeoutException) { }
            catch (error: ExecutionException) { throw (error.cause ?: error) }
        }
    }
    override fun close() {
        cancel()
        if (Thread.currentThread() !== worker) worker.join(35_000)
        check(!worker.isAlive) { "Previous wireless preparation has not stopped" }
    }
}

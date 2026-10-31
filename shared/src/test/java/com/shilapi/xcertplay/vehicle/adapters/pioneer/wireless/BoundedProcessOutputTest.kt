package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InterruptedIOException
import org.junit.Assert.*
import org.junit.Test

class BoundedProcessOutputTest {
    private class Helper(private val running: Boolean) : Process() {
        var destroyed = false
        override fun getInputStream() = ByteArrayInputStream("frequency_mhz=5180\n".toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(byteArrayOf())
        override fun getOutputStream() = ByteArrayOutputStream()
        override fun waitFor(): Int = error("Must not wait without a deadline")
        override fun exitValue(): Int = if (running) throw IllegalThreadStateException() else 0
        override fun destroy() { destroyed = true }
    }
    @Test fun closedStdoutDoesNotAllowAnUnboundedProcessWait() {
        val process = Helper(true)
        assertThrows(IllegalStateException::class.java) { BoundedProcessOutput.read(process, 50, { false }) }
        assertTrue(process.destroyed)
    }
    @Test fun cancellationStopsTheReadOnlyHelper() {
        val process = Helper(true)
        assertThrows(InterruptedIOException::class.java) { BoundedProcessOutput.read(process, 1000, { true }) }
        assertTrue(process.destroyed)
    }
    @Test fun completedAuditReturnsItsOutput() {
        val process = Helper(false)
        assertEquals("frequency_mhz=5180\n", BoundedProcessOutput.read(process, 1000, { false }))
        assertTrue(process.destroyed)
    }
}

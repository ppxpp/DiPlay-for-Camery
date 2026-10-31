package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.os.Process
import android.content.Context
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Vendor calls run in an isolated ARM32 worker; missing capabilities fail the connection. */
internal object PioneerBlueforceNative {
    fun verifyRuntime() {
        check(com.shilapi.xcertplay.pioneer.CamryAdaptation.enabled()) { "凯美瑞 A 导航适配未启用" }
        check(!Process.is64Bit()) { "先锋蓝牙接口需要32位进程；请使用 armeabi-v7a APK" }
        check(File("/system/lib/libblueforce.so").canRead()) { "先锋蓝牙库不可用" }
    }
    private var context: Context? = null
    private var logger: (String) -> Unit = {}
    private var host: PioneerBlueforceHost? = null
    fun configure(context: Context, log: (String) -> Unit) { this.context=context.applicationContext;logger=log }
    fun open(): String {
        check(host == null) { "Previous Pioneer transport is still open" }
        val candidate = PioneerBlueforceHost(checkNotNull(context))
        return try { candidate.open().also { host=candidate } } catch (failure: Throwable) { candidate.close(); throw failure }
    }
    fun read(maximum: Int,timeout: Int): ByteArray? {
        val current=host ?: return ByteArray(0)
        // null is a normal receive timeout; only an empty array means EOF.
        return current.read(maximum,timeout)
    }
    fun write(data: ByteArray): Int = checkNotNull(host) { "Pioneer transport closed" }.write(data)
    fun address(): String? = host?.address()
    fun stats(): String = host?.stats() ?: "closed"
    fun close(): Int { val owner=host; host=null; return owner?.close() ?: 0 }
    fun stream(target: String, cancelled: AtomicBoolean, log: (String) -> Unit): BlockingDuplexByteStream =
        object : BlockingDuplexByteStream {
            private val closed = AtomicBoolean(false)
            override fun send(data: ByteArray) {
                check(!closed.get() && !cancelled.get()) { "Pioneer transport stopped" }
                check(sameAddress(address(),target)) { "Pioneer target changed" }
                var offset=0
                while(offset<data.size) {
                    check(!closed.get() && !cancelled.get()) { "Pioneer transport stopped" }
                    val size=minOf(4096,data.size-offset)
                    check(write(data.copyOfRange(offset,offset+size))==0) { "Pioneer serial send failed" }
                    offset+=size
                }
            }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? =
                if(closed.get() || cancelled.get()) ByteArray(0)
                else read(maxBytes.coerceIn(1,65536),timeoutMillis.coerceIn(0,250).toInt())
            override fun close() { closed.set(true) }
        }
    fun sameAddress(first: String?, second: String): Boolean =
        first?.filter { it.isLetterOrDigit() }?.equals(second.filter { it.isLetterOrDigit() }, true) == true
}

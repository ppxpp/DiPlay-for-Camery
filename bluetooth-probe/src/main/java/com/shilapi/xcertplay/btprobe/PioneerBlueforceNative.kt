package com.shilapi.xcertplay.btprobe

import android.os.Process
import android.content.Context
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** Only the verified 32-bit vendor ABI is allowed to execute. */
internal object PioneerBlueforceNative {
    private const val EXPECTED = "7143ccf70ebb575cbec561bc25964fddc806ed3cbe913e98a04206bf48c6658e"
    fun loadVerified() {
        check(PioneerBinder.firmwareKnown()) { "固件未匹配，停止 native ABI 调用" }
        check(!Process.is64Bit()) { "原生先锋库为 ARM32；请使用 armeabi-v7a APK" }
        val digest = MessageDigest.getInstance("SHA-256")
        File("/system/lib/libblueforce.so").inputStream().use { input ->
            val bytes = ByteArray(16384)
            while (true) { val n = input.read(bytes); if (n < 0) break; digest.update(bytes, 0, n) }
        }
        check(digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) } == EXPECTED) {
            "libblueforce.so SHA-256 不匹配，停止 ABI 调用"
        }
        System.loadLibrary("pioneer_blueforce")
    }
    private var context: Context? = null
    private var logger: (String) -> Unit = {}
    private var host: PioneerBlueforceHost? = null
    private var requireHost = true
    fun configure(context: Context, log: (String) -> Unit) { this.context=context.applicationContext;logger=log }
    private external fun nativeOpen(): String
    private external fun nativeRead(maximum: Int, timeout: Int): ByteArray?
    private external fun nativeWrite(data: ByteArray): Int
    private external fun nativeAddress(): String?
    private external fun nativeStats(): String
    private external fun nativeClose(): Int
    fun open(): String {
        if (!requireHost) {
            val result=nativeOpen()
            if(result=="OK") return result
            check(result.contains("namespace") || result.contains("not accessible")) { result }
            requireHost=true
            logger("应用命名空间限制，改用相同 UID 的独立原生宿主；不申请 root")
        }
        logger("使用同 UID 原生宿主收发；root 仅用于服务内部回调处理")
        check(host==null) { "宿主未关闭" }
        return PioneerBlueforceHost(checkNotNull(context)).let { candidate ->
            val result=candidate.open();host=candidate;logger("独立 Blueforce 宿主注册成功");result
        }
    }
    fun read(maximum: Int,timeout: Int): ByteArray? = if(host!=null) host!!.read(maximum,timeout) else nativeRead(maximum,timeout)
    fun write(data: ByteArray): Int = host?.write(data) ?: nativeWrite(data)
    fun address(): String? = if(host!=null) host!!.address() else nativeAddress()
    fun stats(): String = host?.stats() ?: nativeStats()
    fun close(): Int = host?.let { try { it.close() } finally { host=null } } ?: nativeClose()

    fun stream(target: String, cancelled: AtomicBoolean, log: (String) -> Unit): BlockingDuplexByteStream =
        object : BlockingDuplexByteStream {
            private val closed = AtomicBoolean(false)
            override fun send(data: ByteArray) {
                check(!closed.get() && !cancelled.get()) { "测试已停止" }
                check(sameAddress(address(), target)) { "串口目标地址变化，停止发送" }
                data.asList().chunked(4096).forEach { chunk ->
                    check(!closed.get() && !cancelled.get()) { "测试已停止" }
                    val result = write(chunk.toByteArray())
                    log("Blueforce TX completed=$result bytes=${chunk.size}（等待504发送结果，非手机协议 ACK）")
                    check(result == 0) { "Blueforce 写入未受理：$result" }
                }
            }
            override fun recv(maxBytes: Int, timeoutMillis: Long): ByteArray? {
                if (closed.get() || cancelled.get()) return ByteArray(0)
                return read(maxBytes.coerceIn(1, 65536), timeoutMillis.coerceIn(0, 250).toInt())
            }
            // The session controller owns native registration and disconnect.
            override fun close() { closed.set(true) }
        }
    fun sameAddress(first: String?, second: String): Boolean =
        first?.filter { it.isLetterOrDigit() }?.equals(second.filter { it.isLetterOrDigit() }, true) == true
}

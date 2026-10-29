package com.shilapi.xcertplay.btprobe

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.system.Os
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import android.system.StructPollfd
import android.system.OsConstants
import java.util.UUID

/** Same-UID standalone executable avoids the Android app's private-library namespace. */
internal class PioneerBlueforceHost(private val context: Context) {
    private var child: java.lang.Process? = null
    private var control: LocalSocket? = null
    private var receiver: LocalSocket? = null
    private lateinit var input: DataInputStream
    private lateinit var output: DataOutputStream
    private val controlLock=Any()
    fun open(): String {
        try {
            val binary=File(context.codeCacheDir,"blueforce-host")
            context.assets.open("blueforce-host").use { src -> binary.outputStream().use { src.copyTo(it) } }
            Os.chmod(binary.path,0x1c0)
            val name="diplay-bf-${UUID.randomUUID()}"
            val process=ProcessBuilder(binary.path,name,Process.myUid().toString()).redirectErrorStream(true).start()
            child=process
            val deadline=SystemClock.elapsedRealtime()+10000
            while(true) {
                val socket=LocalSocket()
                try {
                    socket.connect(LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT))
                    check(socket.peerCredentials.uid==Process.myUid()) { "宿主 UID 不匹配" }
                    socket.soTimeout=10000;control=socket;break
                } catch(failure: Exception) {
                    socket.close()
                    val exited=runCatching { process.exitValue() }.getOrNull()
                    check(exited==null) { "宿主退出=$exited ${process.inputStream.bufferedReader().readText().take(1000)}" }
                    check(SystemClock.elapsedRealtime()<deadline) { "宿主启动超时：${failure.message}" }
                    Thread.sleep(50)
                }
            }
            input=DataInputStream(control!!.inputStream);output=DataOutputStream(control!!.outputStream)
            output.writeByte('C'.code);output.flush()
            val status=text()
            check(status=="OK") { status }
            val rx=LocalSocket()
            receiver=rx
            rx.connect(LocalSocketAddress(name,LocalSocketAddress.Namespace.ABSTRACT))
            check(rx.peerCredentials.uid==Process.myUid()) { "接收宿主 UID 不匹配" }
            rx.soTimeout=250;rx.outputStream.write('R'.code);rx.outputStream.flush()
            return "OK"
        } catch(failure: Throwable) { close();throw failure }
    }
    private fun bytes(size: Int): ByteArray {
        val socket=checkNotNull(control) { "宿主控制连接已关闭" }
        val deadline=SystemClock.elapsedRealtime()+5000
        val result=ByteArray(size);var offset=0
        try {
            while(offset<size) {
                val remaining=(deadline-SystemClock.elapsedRealtime()).coerceAtMost(1000).toInt()
                check(remaining>0) { "原生宿主调用超过5秒；已停止，需检查蓝牙服务" }
                val fd=StructPollfd().apply { this.fd=socket.fileDescriptor;events=OsConstants.POLLIN.toShort() }
                if(Os.poll(arrayOf(fd),remaining)==0)continue
                check(fd.revents.toInt() and OsConstants.POLLIN != 0) { "原生宿主控制通道已断开" }
                val count=input.read(result,offset,size-offset)
                check(count>0) { "原生宿主已退出" };offset+=count
            }
            return result
        } catch(failure: Throwable) { abort();throw failure }
    }
    private fun integer(): Int = java.nio.ByteBuffer.wrap(bytes(4)).int
    private fun text(): String {
        val size=integer();check(size in 0..4096) { "宿主回复长度异常" }
        return bytes(size).toString(Charsets.UTF_8)
    }
    private fun abort() {
        runCatching { receiver?.close() };receiver=null
        runCatching { control?.close() };control=null
        child?.destroy()
    }
    fun read(maximum: Int,timeout: Int): ByteArray? {
        val rx=receiver ?: return ByteArray(0)
        val fd=StructPollfd().apply { this.fd=rx.fileDescriptor;events=OsConstants.POLLIN.toShort() }
        if(Os.poll(arrayOf(fd),timeout.coerceAtLeast(0))==0) return null
        if(fd.revents.toInt() and OsConstants.POLLIN == 0) return ByteArray(0)
        val data=ByteArray(maximum.coerceIn(1,65536));val n=rx.inputStream.read(data)
        return if(n<0) ByteArray(0) else data.copyOf(n)
    }
    fun write(data: ByteArray): Int = synchronized(controlLock) {
        output.writeByte('W'.code);output.writeInt(data.size);output.write(data);output.flush();integer()
    }
    fun address(): String? = synchronized(controlLock) {
        output.writeByte('A'.code);output.flush();text().takeIf { it.isNotBlank() }
    }
    fun stats(): String = synchronized(controlLock) {
        output.writeByte('S'.code);output.flush();text()
    }
    fun close(): Int {
        val result=0
        synchronized(controlLock) {
            // Closing the sockets releases the host without another potentially blocked RPC.
            abort()
        }
        child?.let { process ->
            val end=SystemClock.elapsedRealtime()+500
            while(runCatching { process.exitValue() }.isFailure&&SystemClock.elapsedRealtime()<end) Thread.sleep(20)
            if(runCatching { process.exitValue() }.isFailure) process.destroy()
        }
        child=null
        return result
    }
}

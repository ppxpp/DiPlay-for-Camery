package com.shilapi.xcertplay.btprobe

import android.content.Context
import android.os.IBinder
import android.os.Parcel
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipFile

internal enum class HotspotProbeAction {
    DIAG, START, FIVE_GHZ, FIREWALL, STATUS, STOP
}

/** Fixed-purpose root dispatch; the action is an enum, never arbitrary user text. */
internal class RootCommand(private val context: Context, private val log: (String) -> Unit) {
    fun available(): Boolean = runCatching { binder() != null }.getOrDefault(false)

    fun run(action: HotspotProbeAction, timeoutMillis: Long = 180_000): String {
        val output = File(context.filesDir, "hotspot-probe-${System.nanoTime()}.txt")
        output.delete()
        val classpath = rootClasspath()
        val command = "CLASSPATH=${quote(classpath)} /system/bin/app_process /system/bin " +
            "com.shilapi.xcertplay.btprobe.HotspotProbeHelper ${action.name} " +
            "${quote(context.packageName)} ${quote(output.path)} </dev/null >/dev/null 2>&1 &"
        log("root dispatch action=${action.name} output=${output.path}")
        dispatch(command)
        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        try {
            while (SystemClock.elapsedRealtime() < deadline) {
                if (output.isFile && output.length() > 0) {
                    val text = runCatching { output.readText() }.getOrNull()
                    if (text != null && text.contains("RESULT:")) return text
                }
                Thread.sleep(100)
            }
            error("root helper 超时：action=${action.name}；检查 pioneer.pfilesdoperator / SELinux / app_process classpath")
        } finally {
            output.delete()
        }
    }

    private fun binder(): IBinder? = Class.forName("android.os.ServiceManager")
        .getMethod("checkService", String::class.java)
        .invoke(null, "pioneer.pfilesdoperator") as? IBinder

    private fun dispatch(command: String) {
        val binder = binder() ?: error("pioneer.pfilesdoperator 不存在")
        val bytes = (command + '\u0000').toByteArray(Charsets.UTF_8)
        require(bytes.size <= 4096 && '\u0000' !in command) { "root command too long" }
        val encoded = ByteBuffer.allocate(((bytes.size + 3) and -4) + 12)
            .order(ByteOrder.LITTLE_ENDIAN)
            .putInt(Process.myPid())
            .putInt(bytes.size)
            .putInt(0)
            .put(bytes)
            .array()
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.unmarshall(encoded, 0, encoded.size)
            data.setDataPosition(0)
            check(binder.transact(14, data, reply, 0) && reply.dataSize() == 4) {
                "root dispatch rejected"
            }
            reply.setDataPosition(0)
            val result = reply.readInt()
            check(result == 0) { "root dispatch failed code=$result" }
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * app_process only opens classes.dex from an APK path. The probe APK is multidex and the
     * helper may land in classes4.dex, so extract every dex and put all of them on CLASSPATH.
     */
    private fun rootClasspath(): String {
        val apk = File(context.applicationInfo.sourceDir)
        val directory = File(context.filesDir, "root-dex-${apk.lastModified()}-${apk.length()}").apply { mkdirs() }
        if (!context.assets.list("")!!.contains("hotspot-helper.dex")) {
            val dexFiles = mutableListOf<File>()
            ZipFile(apk).use { zip ->
                zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }
                    .sortedBy { it.name }.forEach { entry ->
                        val dex = File(directory, entry.name)
                        if (!dex.isFile) zip.getInputStream(entry).use { input -> dex.outputStream().use { input.copyTo(it) } }
                        dexFiles += dex
                    }
            }
            return dexFiles.joinToString(":") { it.path }
        }
        val target = File(directory, "hotspot-helper.dex")
        if (!target.isFile) {
            val temporary = File(directory, "hotspot-helper.tmp")
            context.assets.open("hotspot-helper.dex").use { input ->
                temporary.outputStream().use { output -> input.copyTo(output) }
            }
            check(temporary.renameTo(target)) { "helper dex publish failed" }
        }
        return target.path
    }

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"
}

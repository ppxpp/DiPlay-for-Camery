package com.shilapi.xcertplay.installer.adb

import android.content.Context
import com.cgutman.adblib.AdbConnection
import com.cgutman.adblib.AdbCrypto
import com.cgutman.adblib.AdbStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException

interface AdbSession {
    fun connect(): String
    fun shell(command: String): String
    fun packagePaths(packageName: String): List<String>
    fun packageVersionCode(packageName: String): Long?
    fun remoteFileSize(remotePath: String): Long?
    fun readRemoteFile(remotePath: String, localFile: File): Long
    fun installApk(apk: File): String
    fun installApkSet(apks: List<File>): String
    fun installSelf(): String
    fun uninstallPackage(packageName: String): String
    fun uninstallSelf(): String
    fun installExisting(packageName: String): String
    fun appops(packageName: String, op: String, mode: String): String
    fun close()
}

abstract class BaseAdbSession(protected val context: Context) : AdbSession {
    protected var adb: AdbConnection? = null

    protected fun attach(connection: AdbConnection): String {
        adb = connection
        val model = shell("getprop ro.product.model").trim()
        return "已连接：$model"
    }

    override fun shell(command: String): String {
        val connection = adb ?: error("ADB 尚未连接")
        val stream = connection.open("shell:$command")
        return readStream(stream)
    }

    override fun packagePaths(packageName: String): List<String> =
        shell("pm path $packageName")
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()

    override fun packageVersionCode(packageName: String): Long? {
        val output = shell("dumpsys package $packageName | grep versionCode")
        return Regex("versionCode=(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull()
    }

    override fun remoteFileSize(remotePath: String): Long? =
        shell("stat -c %s ${quote(remotePath)}").trim().toLongOrNull()

    override fun readRemoteFile(remotePath: String, localFile: File): Long {
        val connection = adb ?: error("ADB 尚未连接")
        localFile.parentFile?.mkdirs()
        val stream = connection.open("sync:")
        var total = 0L
        try {
            stream.write(syncPayload("RECV", remotePath.toByteArray(Charsets.UTF_8)))
            val reader = SyncReader(stream)
            localFile.outputStream().use { output ->
                while (true) {
                    val id = String(reader.readFully(4), Charsets.US_ASCII)
                    val length = le32ToInt(reader.readFully(4))
                    when (id) {
                        "DATA" -> {
                            val chunk = reader.readFully(length)
                            output.write(chunk)
                            total += chunk.size
                        }
                        "DONE" -> return@use
                        "FAIL" -> {
                            val message = if (length in 0..65536) {
                                String(reader.readFully(length))
                            } else {
                                ""
                            }
                            error("读取失败：${message.ifBlank { "未知错误" }}")
                        }
                        else -> error("读取失败：$id")
                    }
                }
            }
        } finally {
            runCatching { stream.close() }
        }
        return total
    }

    override fun installApk(apk: File): String = installApkSet(listOf(apk))

    override fun installApkSet(apks: List<File>): String {
        check(apks.isNotEmpty()) { "找不到安装包" }
        val stamp = System.currentTimeMillis()
        if (apks.size == 1) {
            val remotePath = "/data/local/tmp/diplay_install_$stamp.apk"
            pushFile(apks.first(), remotePath)
            return try {
                shell("pm install -r -t $remotePath")
            } finally {
                runCatching { shell("rm -f $remotePath") }
            }
        }

        val createOut = shell("pm install-create -r -t")
        val sessionId = Regex("\\[(\\d+)]").find(createOut)?.groupValues?.get(1)
            ?: error("创建安装会话失败：${createOut.trim()}")
        val remotePaths = mutableListOf<String>()
        return try {
            apks.forEachIndexed { index, file ->
                val remote = "/data/local/tmp/diplay_install_${stamp}_$index.apk"
                pushFile(file, remote)
                remotePaths += remote
                val writeOut = shell(
                    "pm install-write -S ${file.length()} $sessionId ${file.name} $remote",
                )
                if (!writeOut.contains("Success", ignoreCase = true)) {
                    error("写入安装包失败：${writeOut.trim()}")
                }
            }
            shell("pm install-commit $sessionId")
        } finally {
            remotePaths.forEach { runCatching { shell("rm -f $it") } }
        }
    }

    private fun pushFile(localFile: File, remotePath: String) {
        val connection = adb ?: error("ADB 尚未连接")
        val stream = connection.open("sync:")
        try {
            val maxData = connection.maxData.coerceAtLeast(1024)
            val chunkSize = minOf(64 * 1024, maxData - 8)

            stream.write(syncPayload("SEND", "$remotePath,0644".toByteArray(Charsets.UTF_8)))

            FileInputStream(localFile).use { input ->
                val buffer = ByteArray(chunkSize)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val chunk = buffer.copyOf(read)
                    stream.write(syncPayload("DATA", chunk))
                }
            }

            val mtime = (localFile.lastModified() / 1000).toInt()
            stream.write(syncPayload("DONE", le32(mtime)))
            readSyncResult(SyncReader(stream))
        } finally {
            runCatching { stream.close() }
        }
    }

    private fun syncPayload(id: String, payload: ByteArray): ByteArray =
        id.toByteArray(Charsets.US_ASCII) + le32(payload.size) + payload

    private fun le32(value: Int): ByteArray = byteArrayOf(
        (value and 0xff).toByte(),
        ((value ushr 8) and 0xff).toByte(),
        ((value ushr 16) and 0xff).toByte(),
        ((value ushr 24) and 0xff).toByte(),
    )

    private fun le32ToInt(bytes: ByteArray): Int =
        (bytes[0].toInt() and 0xff) or
            ((bytes[1].toInt() and 0xff) shl 8) or
            ((bytes[2].toInt() and 0xff) shl 16) or
            ((bytes[3].toInt() and 0xff) shl 24)

    private fun readSyncResult(reader: SyncReader) {
        val id = String(reader.readFully(4), Charsets.US_ASCII)
        if (id == "OKAY") return
        if (id == "FAIL") {
            val length = le32ToInt(reader.readFully(4))
            val message = if (length in 0..65536) String(reader.readFully(length)) else ""
            error("写入失败：${message.ifBlank { "未知错误" }}")
        }
        error("写入失败：$id")
    }

    private class SyncReader(private val stream: AdbStream) {
        private var buffer = ByteArray(0)
        private var offset = 0

        fun readFully(count: Int): ByteArray {
            val result = ByteArray(count)
            var filled = 0
            while (filled < count) {
                if (offset >= buffer.size) {
                    buffer = stream.read()
                    offset = 0
                    if (buffer.isEmpty()) continue
                }
                val take = minOf(count - filled, buffer.size - offset)
                System.arraycopy(buffer, offset, result, filled, take)
                offset += take
                filled += take
            }
            return result
        }
    }

    override fun installSelf(): String =
        installApk(File(context.applicationInfo.sourceDir))

    override fun uninstallPackage(packageName: String): String {
        val result = shell("pm uninstall $packageName")
        if (result.contains("Success", ignoreCase = true)) return result
        val fallback = shell("pm uninstall --user 0 $packageName")
        return "$result\n$fallback"
    }

    override fun uninstallSelf(): String = uninstallPackage(context.packageName)

    override fun installExisting(packageName: String): String =
        shell("pm install-existing $packageName")

    override fun appops(packageName: String, op: String, mode: String): String =
        shell("appops set $packageName $op $mode")

    private fun quote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    protected fun readStream(stream: AdbStream): String {
        val output = StringBuilder()
        try {
            while (true) {
                val data = stream.read()
                output.append(String(data, Charsets.UTF_8))
            }
        } catch (_: IOException) {
            // The remote side closes the stream when the command finishes.
        } finally {
            runCatching { stream.close() }
        }
        return output.toString()
    }

    protected fun loadCrypto(): AdbCrypto {
        val base64 = AndroidAdbBase64()
        val privateKey = File(context.filesDir, "adbkey")
        val publicKey = File(context.filesDir, "adbkey.pub")

        if (privateKey.isFile && publicKey.isFile) {
            try {
                return AdbCrypto.loadAdbKeyPair(base64, privateKey, publicKey)
            } catch (_: Exception) {
                privateKey.delete()
                publicKey.delete()
            }
        }

        return AdbCrypto.generateAdbKeyPair(base64).also {
            it.saveAdbKeyPair(privateKey, publicKey)
        }
    }

    override fun close() {
        runCatching { adb?.close() }
        adb = null
    }
}

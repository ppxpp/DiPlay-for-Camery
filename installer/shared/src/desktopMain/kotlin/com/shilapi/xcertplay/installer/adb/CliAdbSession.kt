package com.shilapi.xcertplay.installer.adb

import java.io.File

class CliAdbSession(
    private val binary: File,
    private val serial: String,
    private val environment: Map<String, String>,
) : AdbSession {
    private val exec = AdbExec(binary, environment, serial)

    override fun connect(): String {
        val model = shell("getprop ro.product.model").trim()
        if (model.contains("error", ignoreCase = true) ||
            model.contains("offline", ignoreCase = true) ||
            model.contains("unauthorized", ignoreCase = true)
        ) {
            error(model.ifBlank { "无法连接设备" })
        }
        return "已连接：$model"
    }

    override fun shell(command: String): String =
        exec.run("shell", command)

    override fun packagePaths(packageName: String): List<String> {
        requirePackage(packageName)
        return shell("pm path $packageName")
            .lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("package:") }
            .map { it.removePrefix("package:") }
            .filter { it.isNotBlank() }
            .toList()
    }

    override fun packageVersionCode(packageName: String): Long? {
        requirePackage(packageName)
        val output = shell("dumpsys package $packageName")
        return Regex("versionCode=(\\d+)").find(output)?.groupValues?.get(1)?.toLongOrNull()
    }

    override fun remoteFileSize(remotePath: String): Long? =
        shell("stat -c %s ${quote(remotePath)}").trim().toLongOrNull()

    override fun readRemoteFile(remotePath: String, localFile: File): Long {
        localFile.parentFile?.mkdirs()
        localFile.delete()
        val output = exec.run(
            "pull",
            remotePath,
            localFile.absolutePath,
            timeoutSeconds = 600,
        )
        if (!localFile.isFile || output.contains("error", ignoreCase = true)) {
            error(output.trim().ifBlank { "读取失败：$remotePath" })
        }
        return localFile.length()
    }

    override fun installApk(apk: File): String = installApkSet(listOf(apk))

    override fun installApkSet(apks: List<File>): String {
        check(apks.isNotEmpty()) { "找不到安装包" }
        return if (apks.size == 1) {
            exec.run(
                "install",
                "-r",
                "-t",
                apks.first().absolutePath,
                timeoutSeconds = 600,
            )
        } else {
            exec.run(
                "install-multiple",
                "-r",
                "-t",
                *apks.map { it.absolutePath }.toTypedArray(),
                timeoutSeconds = 600,
            )
        }
    }

    override fun uninstallPackage(packageName: String): String {
        requirePackage(packageName)
        val result = exec.run("uninstall", packageName)
        if (result.contains("Success", ignoreCase = true)) return result
        val fallback = shell("pm uninstall --user 0 $packageName")
        return "$result\n$fallback"
    }

    override fun installExisting(packageName: String): String {
        requirePackage(packageName)
        return shell("pm install-existing $packageName")
    }

    override fun appops(packageName: String, op: String, mode: String): String {
        requirePackage(packageName)
        require(op.matches(Regex("[A-Za-z0-9_]+"))) { "非法的 appops 操作：$op" }
        require(mode.matches(Regex("[A-Za-z0-9_]+"))) { "非法的 appops 模式：$mode" }
        return shell("appops set $packageName $op $mode")
    }

    override fun close() = Unit

    private fun requirePackage(packageName: String) {
        require(packageName.matches(Regex("[A-Za-z0-9._]+"))) {
            "非法的包名：$packageName"
        }
    }

    private fun quote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"
}

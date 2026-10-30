package com.shilapi.xcertplay.installer.settings

import java.io.File

object BackupDirectory {
    fun validate(dir: File): Result<Unit> = runCatching {
        if (!dir.exists() && !dir.mkdirs()) {
            error("无法创建备份目录：${dir.absolutePath}")
        }
        if (!dir.isDirectory) error("备份位置不是文件夹：${dir.absolutePath}")
        if (!dir.canWrite()) error("备份目录不可写：${dir.absolutePath}")

        val probe = File(dir, ".diplay-write-test")
        try {
            probe.writeText("ok")
            probe.delete()
        } catch (error: Exception) {
            probe.delete()
            throw IllegalStateException("备份目录不可写：${dir.absolutePath}", error)
        }
    }

    fun usableSpace(dir: File): Long =
        runCatching { dir.usableSpace }.getOrDefault(0L)
}

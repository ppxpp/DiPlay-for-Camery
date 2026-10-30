package com.shilapi.xcertplay.installer.adb

import java.io.File

interface AdbSession {
    fun connect(): String
    fun shell(command: String): String
    fun packagePaths(packageName: String): List<String>
    fun packageVersionCode(packageName: String): Long?
    fun remoteFileSize(remotePath: String): Long?
    fun readRemoteFile(remotePath: String, localFile: File): Long
    fun installApk(apk: File): String
    fun installApkSet(apks: List<File>): String
    fun uninstallPackage(packageName: String): String
    fun installExisting(packageName: String): String
    fun appops(packageName: String, op: String, mode: String): String
    fun close()
}

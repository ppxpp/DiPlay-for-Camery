package com.shilapi.xcertplay.installer.platform

import com.shilapi.xcertplay.installer.adb.AdbController
import com.shilapi.xcertplay.installer.update.AppUpdate
import java.io.File

interface PlatformContext {
    val isDesktop: Boolean
    val isDebuggable: Boolean
    val appVersionName: String
    val appVersionCode: Long
    val deviceId: String
    val updatePlatform: String

    val filesDir: File
    val cacheDir: File
    val payloadDir: File
    val defaultBackupDir: File
    val settingsFile: File
    val updatesDir: File

    val supportsBackupDirectoryPicker: Boolean
    val supportsUsbDriverHelp: Boolean

    fun createAdbController(): AdbController
    fun openUsbDriverHelp(): Boolean

    suspend fun checkUpdate(currentVersionCode: Long): AppUpdate?
    suspend fun downloadUpdate(update: AppUpdate, onProgress: (Int) -> Unit): File?
    suspend fun installUpdate(file: File): Result<Unit>

    fun pickDirectory(initial: File?): File?
    fun openDirectory(dir: File): Boolean
}

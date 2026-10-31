package com.shilapi.xcertplay.installer.backup

import com.shilapi.xcertplay.installer.adb.AdbSession
import com.shilapi.xcertplay.installer.data.model.InstallApp
import com.shilapi.xcertplay.installer.data.model.InstallKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class BackupStatus {
    Pending,
    Scanning,
    BackingUp,
    Done,
    Missing,
    Failed,
}

data class AppBackupState(
    val appId: String,
    val label: String,
    val status: BackupStatus = BackupStatus.Pending,
    val bytes: Long = 0L,
    val fileCount: Int = 0,
    val message: String? = null,
)

data class BackupUiState(
    val apps: Map<String, AppBackupState> = emptyMap(),
    val running: Boolean = false,
    val backupDir: String? = null,
    val error: String? = null,
)

class BackupCoordinator(
    private val backupRoot: File,
    private val session: AdbSession,
    private val kit: InstallKit,
) {
    private val mutex = Mutex()
    private val json = Json { prettyPrint = true }
    private val _state = MutableStateFlow(
        BackupUiState(
            apps = kit.apps.associate { app ->
                app.id to AppBackupState(appId = app.id, label = app.replacesLabel)
            },
        ),
    )
    val state: StateFlow<BackupUiState> = _state.asStateFlow()

    suspend fun backup() = mutex.withLock {
        if (_state.value.running) return@withLock
        _state.update { it.copy(running = true, error = null, backupDir = null) }

        try {
            val root = withContext(Dispatchers.IO) {
                File(backupRoot, timestamp()).also { it.mkdirs() }
            }
            val entries = mutableListOf<BackupAppManifest>()

            for (app in kit.apps) {
                backupApp(app, root)?.let { entries += it }
            }

            val deviceModel = withContext(Dispatchers.IO) {
                runCatching { session.shell("getprop ro.product.model").trim() }.getOrDefault("")
            }
            val deviceSdk = withContext(Dispatchers.IO) {
                runCatching { session.shell("getprop ro.build.version.sdk").trim() }.getOrDefault("")
            }
            val manifest = BackupManifest(
                createdAt = timestamp(),
                device = BackupDevice(model = deviceModel, sdk = deviceSdk),
                kitId = kit.kitId,
                kitVersion = kit.kitVersion,
                apps = entries,
            )
            withContext(Dispatchers.IO) {
                File(root, "manifest.json").writeText(
                    json.encodeToString(BackupManifest.serializer(), manifest),
                )
            }

            _state.update { it.copy(running = false, backupDir = root.absolutePath) }
        } catch (error: Exception) {
            _state.update {
                it.copy(running = false, error = error.message ?: "备份失败")
            }
        }
    }

    private suspend fun backupApp(app: InstallApp, root: File): BackupAppManifest? {
        updateApp(app.id) { it.copy(status = BackupStatus.Scanning, message = null) }
        return try {
            val paths = withContext(Dispatchers.IO) { session.packagePaths(app.packageName) }
            if (paths.isEmpty()) {
                updateApp(app.id) {
                    it.copy(status = BackupStatus.Missing, message = "车机上没有这个应用")
                }
                return null
            }

            val versionCode = withContext(Dispatchers.IO) {
                session.packageVersionCode(app.packageName)
            }
            val appDir = withContext(Dispatchers.IO) {
                File(root, safeName(app.packageName)).also { it.mkdirs() }
            }
            val files = mutableListOf<BackupFileManifest>()
            var totalBytes = 0L

            updateApp(app.id) { it.copy(status = BackupStatus.BackingUp) }

            paths.forEachIndexed { index, remotePath ->
                val remoteSize = withContext(Dispatchers.IO) { session.remoteFileSize(remotePath) }
                val name = File(remotePath).name.ifBlank { "base.apk" }
                val localName = if (index == 0 || !File(appDir, name).exists()) {
                    name
                } else {
                    "${index}_$name"
                }
                val localFile = File(appDir, localName)
                val copied = withContext(Dispatchers.IO) {
                    session.readRemoteFile(remotePath, localFile)
                }
                if (remoteSize != null && copied != remoteSize) {
                    throw IllegalStateException("文件复制不完整")
                }
                totalBytes += copied
                files += BackupFileManifest(
                    name = localName,
                    remotePath = remotePath,
                    sizeBytes = copied,
                )
                updateApp(app.id) {
                    it.copy(
                        bytes = totalBytes,
                        fileCount = index + 1,
                    )
                }
            }

            updateApp(app.id) {
                it.copy(
                    status = BackupStatus.Done,
                    bytes = totalBytes,
                    fileCount = paths.size,
                    message = null,
                )
            }

            BackupAppManifest(
                packageName = app.packageName,
                label = app.replacesLabel,
                versionCode = versionCode ?: 0L,
                isSystemApp = paths.any { path ->
                    path.startsWith("/system/") ||
                        path.startsWith("/vendor/") ||
                        path.startsWith("/product/")
                },
                files = files,
                backedUpAt = timestamp(),
            )
        } catch (error: Exception) {
            updateApp(app.id) {
                it.copy(
                    status = BackupStatus.Failed,
                    message = error.message ?: "备份失败",
                )
            }
            null
        }
    }

    private fun updateApp(appId: String, transform: (AppBackupState) -> AppBackupState) {
        _state.update { current ->
            val app = current.apps[appId] ?: return@update current
            current.copy(apps = current.apps + (appId to transform(app)))
        }
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
}

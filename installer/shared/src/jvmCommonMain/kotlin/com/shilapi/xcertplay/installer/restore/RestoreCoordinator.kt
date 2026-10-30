package com.shilapi.xcertplay.installer.restore

import com.shilapi.xcertplay.installer.adb.AdbSession
import com.shilapi.xcertplay.installer.backup.BackupAppEntry
import com.shilapi.xcertplay.installer.backup.BackupSession
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class AppRestoreStatus {
    Pending,
    Uninstalling,
    Installing,
    Enabling,
    Verifying,
    Done,
    Failed,
}

data class AppRestoreState(
    val packageName: String,
    val label: String,
    val status: AppRestoreStatus = AppRestoreStatus.Pending,
    val message: String? = null,
)

data class RestoreUiState(
    val apps: Map<String, AppRestoreState> = emptyMap(),
    val running: Boolean = false,
    val finished: Boolean = false,
)

class RestoreCoordinator(
    private val session: AdbSession,
    private val backup: BackupSession,
    private val selectedPackages: List<String>,
) {
    private val mutex = Mutex()
    private val entries: List<BackupAppEntry> = selectedPackages.mapNotNull { pkg ->
        backup.apps.firstOrNull { it.packageName == pkg }
    }

    private val _state = MutableStateFlow(
        RestoreUiState(
            apps = entries.associate {
                it.packageName to AppRestoreState(it.packageName, it.label)
            },
        ),
    )
    val state: StateFlow<RestoreUiState> = _state.asStateFlow()

    suspend fun restore() = mutex.withLock {
        if (_state.value.running) return@withLock
        _state.update { it.copy(running = true, finished = false) }
        entries.forEach { restoreApp(it) }
        _state.update { it.copy(running = false, finished = true) }
    }

    private suspend fun restoreApp(entry: BackupAppEntry) {
        updateApp(entry.packageName) {
            it.copy(status = AppRestoreStatus.Uninstalling, message = null)
        }
        try {
            if (entry.isSystemApp) {
                updateApp(entry.packageName) { it.copy(status = AppRestoreStatus.Enabling) }
                val out = withContext(Dispatchers.IO) {
                    session.installExisting(entry.packageName)
                }
                if (out.contains("Error", ignoreCase = true) ||
                    out.contains("Failure", ignoreCase = true)
                ) {
                    error(out.trim().ifBlank { "恢复系统应用失败" })
                }
            } else {
                if (!entry.hasFiles) error("备份文件不完整")
                withContext(Dispatchers.IO) {
                    val current = session.packagePaths(entry.packageName)
                    if (current.isNotEmpty()) session.uninstallPackage(entry.packageName)
                }
                updateApp(entry.packageName) { it.copy(status = AppRestoreStatus.Installing) }
                val files = entry.files.map { it.localFile }
                val out = withContext(Dispatchers.IO) { session.installApkSet(files) }
                if (out.contains("Failure", ignoreCase = true) ||
                    out.contains("Error", ignoreCase = true)
                ) {
                    error(out.trim().ifBlank { "恢复安装失败" })
                }
            }

            updateApp(entry.packageName) { it.copy(status = AppRestoreStatus.Verifying) }
            val installed = withContext(Dispatchers.IO) {
                session.packagePaths(entry.packageName)
            }
            if (installed.isEmpty()) error("恢复后没有找到应用")

            updateApp(entry.packageName) {
                it.copy(status = AppRestoreStatus.Done, message = null)
            }
        } catch (error: Exception) {
            updateApp(entry.packageName) {
                it.copy(
                    status = AppRestoreStatus.Failed,
                    message = error.message ?: "恢复失败",
                )
            }
        }
    }

    private fun updateApp(packageName: String, transform: (AppRestoreState) -> AppRestoreState) {
        _state.update { current ->
            val app = current.apps[packageName] ?: return@update current
            current.copy(apps = current.apps + (packageName to transform(app)))
        }
    }
}

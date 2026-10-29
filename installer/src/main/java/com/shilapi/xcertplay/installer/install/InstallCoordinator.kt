package com.shilapi.xcertplay.installer.install

import com.shilapi.xcertplay.installer.adb.AdbSession
import com.shilapi.xcertplay.installer.data.DownloadCoordinator
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

enum class AppInstallStatus {
    Pending,
    Checking,
    Uninstalling,
    Installing,
    Done,
    Failed,
}

data class AppInstallState(
    val appId: String,
    val label: String,
    val status: AppInstallStatus = AppInstallStatus.Pending,
    val message: String? = null,
)

data class InstallUiState(
    val apps: Map<String, AppInstallState> = emptyMap(),
    val running: Boolean = false,
    val finished: Boolean = false,
)

class InstallCoordinator(
    private val session: AdbSession,
    private val kit: InstallKit,
    private val downloads: DownloadCoordinator,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        InstallUiState(
            apps = kit.apps.associate { app ->
                app.id to AppInstallState(appId = app.id, label = app.label)
            },
        ),
    )
    val state: StateFlow<InstallUiState> = _state.asStateFlow()

    suspend fun install() = mutex.withLock {
        if (_state.value.running) return@withLock
        _state.update { it.copy(running = true, finished = false) }

        for (app in kit.apps) {
            installApp(app)
        }

        _state.update { it.copy(running = false, finished = true) }
    }

    private suspend fun installApp(app: InstallApp) {
        updateApp(app.id) {
            it.copy(status = AppInstallStatus.Checking, message = null)
        }

        try {
            if (app.files.isEmpty()) error("安装包信息不完整")
            if (app.files.size > 1) error("暂不支持分片安装")

            val apk = downloads.localFile(app.files.first())
            if (!apk.isFile || apk.length() != app.files.first().sizeBytes) {
                error("安装包不完整，请重新下载")
            }

            val currentPaths = withContext(Dispatchers.IO) {
                session.packagePaths(app.packageName)
            }
            if (currentPaths.isNotEmpty()) {
                updateApp(app.id) {
                    it.copy(status = AppInstallStatus.Uninstalling)
                }
                withContext(Dispatchers.IO) {
                    session.uninstallPackage(app.packageName)
                }
                val remaining = withContext(Dispatchers.IO) {
                    session.packagePaths(app.packageName)
                }
                if (remaining.isNotEmpty()) {
                    error("旧版本卸载失败")
                }
            }

            updateApp(app.id) {
                it.copy(status = AppInstallStatus.Installing)
            }
            val output = withContext(Dispatchers.IO) {
                session.installApk(apk)
            }
            if (output.contains("Failure", ignoreCase = true) ||
                output.contains("Error", ignoreCase = true)
            ) {
                error(output.ifBlank { "安装失败" })
            }

            val installedPaths = withContext(Dispatchers.IO) {
                session.packagePaths(app.packageName)
            }
            if (installedPaths.isEmpty()) error("安装后没有找到应用")

            if (app.versionCode > 0L) {
                val installedCode = withContext(Dispatchers.IO) {
                    session.packageVersionCode(app.packageName)
                }
                if (installedCode != null && installedCode != app.versionCode) {
                    error("安装后版本不一致")
                }
            }

            app.postInstall.forEach { action ->
                if (action.type == "appops") {
                    withContext(Dispatchers.IO) {
                        session.appops(app.packageName, action.op, action.mode)
                    }
                }
            }

            updateApp(app.id) {
                it.copy(status = AppInstallStatus.Done, message = null)
            }
        } catch (error: Exception) {
            updateApp(app.id) {
                it.copy(
                    status = AppInstallStatus.Failed,
                    message = error.message ?: "安装失败",
                )
            }
        }
    }

    private fun updateApp(appId: String, transform: (AppInstallState) -> AppInstallState) {
        _state.update { current ->
            val app = current.apps[appId] ?: return@update current
            current.copy(apps = current.apps + (appId to transform(app)))
        }
    }
}
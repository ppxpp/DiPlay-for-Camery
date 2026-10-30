package com.shilapi.xcertplay.installer.update

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.shilapi.xcertplay.installer.platform.PlatformContext
import com.shilapi.xcertplay.installer.settings.SettingsStore
import java.io.File
import java.time.LocalDate

class UpdateManager(
    private val platform: PlatformContext,
    private val settings: SettingsStore,
) {
    var available by mutableStateOf<AppUpdate?>(null)
        private set

    var checking by mutableStateOf(false)
        private set

    var checkedOnce by mutableStateOf(false)
        private set

    var checkFailed by mutableStateOf(false)
        private set

    var downloading by mutableStateOf(false)
        private set

    var downloadPercent by mutableStateOf<Int?>(null)
        private set

    var downloadFailed by mutableStateOf(false)
        private set

    var readyFile by mutableStateOf<File?>(null)
        private set

    var installError by mutableStateOf<String?>(null)
        private set

    suspend fun autoCheckOncePerDay() {
        if (platform.isDesktop) return
        if (!settings.state.autoCheckUpdate) return
        val today = LocalDate.now().toString()
        if (settings.state.lastAutoCheck == today) return
        val result = runCatching { platform.checkUpdate(platform.appVersionCode) }
        if (result.isSuccess) {
            settings.markAutoCheck(today)
            available = result.getOrNull()
        }
    }

    suspend fun check() {
        checking = true
        checkFailed = false
        checkedOnce = true
        try {
            available = platform.checkUpdate(platform.appVersionCode)
        } catch (_: Exception) {
            checkFailed = true
        } finally {
            checking = false
        }
    }

    suspend fun download() {
        val update = available ?: return
        downloading = true
        downloadFailed = false
        downloadPercent = 0
        try {
            val file = platform.downloadUpdate(update) { percent ->
                downloadPercent = percent
            }
            if (file == null) {
                downloadFailed = true
            } else {
                readyFile = file
            }
        } catch (_: Exception) {
            downloadFailed = true
        } finally {
            downloading = false
            downloadPercent = null
        }
    }

    suspend fun installReady() {
        val file = readyFile ?: return
        installError = null
        platform.installUpdate(file).onFailure {
            installError = it.message ?: "安装更新失败"
        }
    }
}

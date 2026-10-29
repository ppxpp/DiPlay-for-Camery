package com.shilapi.xcertplay.installer.data

import android.content.Context
import com.shilapi.xcertplay.installer.data.model.InstallApp
import com.shilapi.xcertplay.installer.data.model.InstallFile
import com.shilapi.xcertplay.installer.data.model.InstallKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

enum class DownloadStatus {
    Pending,
    Verifying,
    Downloading,
    Ready,
    Failed,
    Invalid,
}

data class AppDownloadState(
    val appId: String,
    val status: DownloadStatus = DownloadStatus.Pending,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null,
)

data class DownloadUiState(
    val apps: Map<String, AppDownloadState> = emptyMap(),
    val isRunning: Boolean = false,
)

class DownloadCoordinator(
    private val context: Context,
    private val kit: InstallKit,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow(
        DownloadUiState(
            apps = kit.apps.associate { app ->
                app.id to AppDownloadState(
                    appId = app.id,
                    totalBytes = app.files.sumOf { it.sizeBytes },
                )
            },
        ),
    )
    val state: StateFlow<DownloadUiState> = _state.asStateFlow()

    suspend fun verifyCachedFiles() = mutex.withLock {
        if (_state.value.isRunning) return@withLock
        _state.update { it.copy(isRunning = true) }
        try {
            for (app in kit.apps) {
                verifyApp(app)
            }
        } finally {
            _state.update { it.copy(isRunning = false) }
        }
    }

    suspend fun downloadAll() = mutex.withLock {
        if (_state.value.isRunning) return@withLock
        _state.update { it.copy(isRunning = true) }
        try {
            for (app in kit.apps) {
                downloadApp(app)
            }
        } finally {
            _state.update { it.copy(isRunning = false) }
        }
    }

    private suspend fun verifyApp(app: InstallApp) {
        if (!app.hasCompleteFile) {
            updateApp(app.id) {
                it.copy(status = DownloadStatus.Invalid, error = "安装包信息不完整，请联系管理员")
            }
            return
        }

        val total = app.files.sumOf { it.sizeBytes }
        var completed = 0L
        updateApp(app.id) {
            it.copy(
                status = DownloadStatus.Verifying,
                totalBytes = total,
                downloadedBytes = 0L,
                error = null,
            )
        }

        for (file in app.files) {
            val target = targetFile(file)
            val valid = withContext(Dispatchers.IO) { isFileValid(file, target) }
            if (!valid) {
                updateApp(app.id) {
                    it.copy(status = DownloadStatus.Pending, downloadedBytes = completed)
                }
                return
            }
            completed += file.sizeBytes
            updateApp(app.id) { it.copy(downloadedBytes = completed) }
        }

        updateApp(app.id) {
            it.copy(
                status = DownloadStatus.Ready,
                downloadedBytes = total,
                error = null,
            )
        }
    }

    private suspend fun downloadApp(app: InstallApp) {
        if (_state.value.apps[app.id]?.status == DownloadStatus.Ready) return
        if (!app.hasCompleteFile) {
            updateApp(app.id) {
                it.copy(status = DownloadStatus.Invalid, error = "安装包信息不完整，请联系管理员")
            }
            return
        }

        val total = app.files.sumOf { it.sizeBytes }
        var completed = 0L
        updateApp(app.id) {
            it.copy(
                status = DownloadStatus.Downloading,
                totalBytes = total,
                downloadedBytes = 0L,
                error = null,
            )
        }

        for (file in app.files) {
            val target = targetFile(file)
            val from = completed
            val success = withContext(Dispatchers.IO) {
                ensureFile(
                    file = file,
                    target = target,
                    onProgress = { fileBytes ->
                        updateApp(app.id) {
                            it.copy(
                                downloadedBytes = (from + fileBytes).coerceAtMost(total),
                            )
                        }
                    },
                )
            }
            if (!success) {
                updateApp(app.id) {
                    it.copy(status = DownloadStatus.Failed, error = "下载失败，请重试")
                }
                return
            }
            completed += file.sizeBytes
            updateApp(app.id) { it.copy(downloadedBytes = completed) }
        }

        updateApp(app.id) {
            it.copy(
                status = DownloadStatus.Ready,
                downloadedBytes = total,
                error = null,
            )
        }
    }

    private fun updateApp(appId: String, transform: (AppDownloadState) -> AppDownloadState) {
        _state.update { current ->
            val app = current.apps[appId] ?: return@update current
            current.copy(apps = current.apps + (appId to transform(app)))
        }
    }

    fun localFile(file: InstallFile): File = targetFile(file)

    fun isCachedReady(app: InstallApp): Boolean =
        app.hasCompleteFile && app.files.all { file ->
            val target = targetFile(file)
            target.isFile && target.length() == file.sizeBytes
        }

    private fun targetFile(file: InstallFile): File {
        val root = File(
            context.filesDir,
            "payload/${kit.kitId}/${kit.channel}/${kit.kitVersion}",
        )
        if (!root.exists()) root.mkdirs()
        val safeName = File(file.name).name.ifBlank { "payload.apk" }
        return File(root, safeName)
    }

    private fun isFileValid(file: InstallFile, target: File): Boolean {
        if (!target.isFile || target.length() != file.sizeBytes || file.sha256.isBlank()) {
            return false
        }
        return sha256(target).equals(file.sha256, ignoreCase = true)
    }

    private fun ensureFile(
        file: InstallFile,
        target: File,
        onProgress: (Long) -> Unit,
    ): Boolean {
        if (isFileValid(file, target)) {
            onProgress(file.sizeBytes)
            return true
        }
        target.delete()

        val temp = File(target.parentFile, "${target.name}.part")
        if (temp.exists() && temp.length() > file.sizeBytes) temp.delete()

        var existing = if (temp.exists()) temp.length() else 0L
        if (existing >= file.sizeBytes && file.sizeBytes > 0L) {
            temp.delete()
            existing = 0L
        }

        val connection = (URL(file.downloadUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DiPlayInstaller/0.1")
            if (existing > 0L) {
                setRequestProperty("Range", "bytes=$existing-")
            }
        }

        try {
            val code = connection.responseCode
            if (code == 416) {
                temp.delete()
                return false
            }
            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                return false
            }

            val append = code == HttpURLConnection.HTTP_PARTIAL && existing > 0L
            if (!append) existing = 0L

            var downloaded = existing
            onProgress(downloaded)
            connection.inputStream.use { input ->
                FileOutputStream(temp, append).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        onProgress(downloaded.coerceAtMost(file.sizeBytes))
                    }
                    output.flush()
                    output.fd.sync()
                }
            }

            if (temp.length() != file.sizeBytes) {
                return false
            }
            if (!sha256(temp).equals(file.sha256, ignoreCase = true)) {
                temp.delete()
                return false
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            return true
        } catch (_: IOException) {
            return false
        } finally {
            connection.disconnect()
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

package com.shilapi.xcertplay.installer.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class AppSettings(
    val schemaVersion: Int = 1,
    val backupDir: String? = null,
    val autoCheckUpdate: Boolean = true,
    val lastAutoCheck: String? = null,
)

class SettingsStore(
    private val file: File,
    private val defaultBackupDir: File,
) {
    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    var state by mutableStateOf(load())
        private set

    val backupDir: File
        get() = state.backupDir?.takeIf { it.isNotBlank() }?.let(::File) ?: defaultBackupDir

    fun setBackupDir(dir: File?) {
        update { it.copy(backupDir = dir?.absolutePath) }
    }

    fun setAutoCheckUpdate(enabled: Boolean) {
        update { it.copy(autoCheckUpdate = enabled) }
    }

    fun markAutoCheck(date: String) {
        update { it.copy(lastAutoCheck = date) }
    }

    private fun update(transform: (AppSettings) -> AppSettings) {
        state = transform(state)
        save(state)
    }

    private fun load(): AppSettings {
        if (!file.isFile) return AppSettings()
        return runCatching {
            json.decodeFromString(AppSettings.serializer(), file.readText())
        }.getOrDefault(AppSettings())
    }

    private fun save(settings: AppSettings) {
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(json.encodeToString(AppSettings.serializer(), settings))
        }
    }
}

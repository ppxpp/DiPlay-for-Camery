package com.shilapi.xcertplay.installer.backup

import kotlinx.serialization.json.Json
import java.io.File

data class BackupFileEntry(
    val name: String,
    val localFile: File,
    val sizeBytes: Long,
)

data class BackupAppEntry(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val isSystemApp: Boolean,
    val files: List<BackupFileEntry>,
) {
    val hasFiles: Boolean = files.isNotEmpty() && files.all { it.localFile.isFile }
    val sizeBytes: Long = files.sumOf { it.sizeBytes }
}

data class BackupSession(
    val id: String,
    val dir: File,
    val createdAt: String,
    val deviceModel: String,
    val apps: List<BackupAppEntry>,
) {
    val totalSizeBytes: Long = apps.sumOf { it.sizeBytes }
    val backedUpApps: List<BackupAppEntry> = apps.filter { it.hasFiles }
}

object BackupRepository {
    private val json = Json { ignoreUnknownKeys = true }

    fun listSessions(root: File): List<BackupSession> {
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) return@mapNotNull null
            runCatching { parseSession(dir, manifest) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    private fun parseSession(dir: File, manifest: File): BackupSession {
        val data = json.decodeFromString(BackupManifest.serializer(), manifest.readText())
        val apps = data.apps.map { app ->
            BackupAppEntry(
                packageName = app.packageName,
                label = app.label.ifBlank { app.packageName },
                versionCode = app.versionCode,
                isSystemApp = app.isSystemApp,
                files = app.files.map { file ->
                    BackupFileEntry(
                        name = file.name,
                        localFile = File(File(dir, safeName(app.packageName)), file.name),
                        sizeBytes = file.sizeBytes,
                    )
                },
            )
        }
        return BackupSession(
            id = dir.name,
            dir = dir,
            createdAt = data.createdAt.ifBlank { dir.name },
            deviceModel = data.device.model,
            apps = apps,
        )
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

package com.shilapi.xcertplay.installer.backup

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
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
    fun listSessions(context: Context): List<BackupSession> {
        val root = File(context.filesDir, "backups")
        val dirs = root.listFiles()?.filter { it.isDirectory }.orEmpty()
        return dirs.mapNotNull { dir ->
            val manifest = File(dir, "manifest.json")
            if (!manifest.isFile) return@mapNotNull null
            runCatching { parseSession(dir, manifest) }.getOrNull()
        }.sortedByDescending { it.createdAt }
    }

    private fun parseSession(dir: File, manifest: File): BackupSession {
        val json = JSONObject(manifest.readText())
        val createdAt = json.optString("createdAt", dir.name)
        val device = json.optJSONObject("device")
        val model = device?.optString("model").orEmpty()
        val appsArray = json.optJSONArray("apps") ?: JSONArray()
        val apps = (0 until appsArray.length()).map { i ->
            val app = appsArray.getJSONObject(i)
            val packageName = app.getString("packageName")
            val filesArray = app.optJSONArray("files") ?: JSONArray()
            val files = (0 until filesArray.length()).map { j ->
                val file = filesArray.getJSONObject(j)
                val name = file.getString("name")
                BackupFileEntry(
                    name = name,
                    localFile = File(File(dir, safeName(packageName)), name),
                    sizeBytes = file.optLong("sizeBytes"),
                )
            }
            BackupAppEntry(
                packageName = packageName,
                label = app.optString("label", packageName),
                versionCode = app.optLong("versionCode", 0L),
                isSystemApp = app.optBoolean("isSystemApp", false),
                files = files,
            )
        }
        return BackupSession(
            id = dir.name,
            dir = dir,
            createdAt = createdAt,
            deviceModel = model,
            apps = apps,
        )
    }

    private fun safeName(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}

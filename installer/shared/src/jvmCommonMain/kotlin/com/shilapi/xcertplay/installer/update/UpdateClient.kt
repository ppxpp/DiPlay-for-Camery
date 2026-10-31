package com.shilapi.xcertplay.installer.update

import com.shilapi.xcertplay.installer.platform.PlatformContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLEncoder
import java.security.MessageDigest

@Serializable
data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val fileName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

@Serializable
private data class UpdateResponse(
    val versionCode: Long = 0L,
    val versionName: String = "",
    val version: String = "",
    val artifacts: List<UpdateArtifact> = emptyList(),
)

@Serializable
private data class UpdateArtifact(
    val name: String = "",
    val url: String = "",
    val size: Long = 0L,
    val sha256: String = "",
    val arch: String? = null,
)

class UpdateClient(private val platform: PlatformContext) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun check(currentVersionCode: Long): AppUpdate? = withContext(Dispatchers.IO) {
        val deviceId = URLEncoder.encode(platform.deviceId, "UTF-8")
        val url = "${UPDATE_BASE}/${platform.updatePlatform}/stable/latest?id=$deviceId"
        val connection = (URI.create(url).toURL().openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 15_000
            readTimeout = 20_000
            instanceFollowRedirects = true
            setRequestProperty("Accept", "application/json")
            setRequestProperty("User-Agent", "DiPlayInstaller/0.2")
        }
        try {
            if (connection.responseCode !in 200..299) {
                error("更新服务器返回 ${connection.responseCode}")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val response = json.decodeFromString(UpdateResponse.serializer(), body)
            if (response.versionCode <= currentVersionCode) return@withContext null

            val candidates = response.artifacts.filter {
                it.name.isNotBlank() && it.url.isNotBlank()
            }
            val preferredExtensions = if (platform.isDesktop) {
                listOf(".zip", ".exe", ".msi")
            } else {
                listOf(".apk")
            }
            val artifact = preferredExtensions.firstNotNullOfOrNull { extension ->
                val matches = candidates.filter { it.name.endsWith(extension, ignoreCase = true) }
                matches.firstOrNull { it.arch.isNullOrBlank() || it.arch == "universal" }
                    ?: matches.firstOrNull()
            } ?: return@withContext null

            AppUpdate(
                versionCode = response.versionCode,
                versionName = response.versionName.ifBlank { response.version },
                fileName = artifact.name,
                downloadUrl = artifact.url,
                sizeBytes = artifact.size,
                sha256 = artifact.sha256,
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        update: AppUpdate,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        val dir = platform.updatesDir.apply { mkdirs() }
        val safeName = File(update.fileName).name.ifBlank { "diplay-installer-${update.versionCode}" }
        val target = File(dir, safeName)
        if (isReady(target, update)) {
            onProgress(100)
            return@withContext target
        }
        target.delete()

        val temp = File(dir, "${target.name}.part")
        temp.delete()
        val connection = (URI.create(update.downloadUrl).toURL().openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "DiPlayInstaller/0.2")
        }
        try {
            if (connection.responseCode !in 200..299) return@withContext null
            val total = update.sizeBytes.takeIf { it > 0L } ?: connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(temp).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        downloaded += read
                        if (total > 0L) {
                            val percent = (downloaded * 100 / total).toInt().coerceIn(0, 100)
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(percent)
                            }
                        }
                    }
                    output.flush()
                    output.fd.sync()
                }
            }
            if (!isReady(temp, update)) {
                temp.delete()
                return@withContext null
            }
            if (!temp.renameTo(target)) {
                temp.copyTo(target, overwrite = true)
                temp.delete()
            }
            onProgress(100)
            target
        } finally {
            connection.disconnect()
        }
    }

    private fun isReady(file: File, update: AppUpdate): Boolean {
        if (!file.isFile) return false
        if (update.sizeBytes > 0L && file.length() != update.sizeBytes) return false
        if (update.sha256.isBlank()) return true
        return sha256(file).equals(update.sha256, ignoreCase = true)
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

    private companion object {
        const val UPDATE_BASE = "https://diplay.mgulu.com/api/v1/apps/installer"
    }
}

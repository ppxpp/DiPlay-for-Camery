package com.shilapi.xcertplay.installer.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

data class AppUpdate(
    val versionCode: Long,
    val versionName: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

object AppUpdateRepository {
    private const val UPDATE_URL =
        "https://diplay.mgulu.com/api/v1/apps/installer/android/stable/latest"

    suspend fun check(context: Context): AppUpdate? = withContext(Dispatchers.IO) {
        runCatching {
            val clientId = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ANDROID_ID,
            ) ?: "unknown"
            val connection = (URL("$UPDATE_URL?id=${Uri.encode(clientId)}").openConnection() as HttpURLConnection)
            checkUpdate(connection, context)
        }.getOrNull()
    }

    private fun checkUpdate(
        connection: HttpURLConnection,
        context: Context,
    ): AppUpdate? {
        connection.requestMethod = "GET"
        connection.connectTimeout = 15_000
        connection.readTimeout = 20_000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("User-Agent", "DiPlayInstaller/0.1")
        try {
            if (connection.responseCode !in 200..299) return null
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val root = JSONObject(body)
            val versionCode = root.optLong("versionCode", 0L)
            val installed = context.packageManager
                .getPackageInfo(context.packageName, 0)
                .longVersionCode
            if (versionCode <= installed) return null

            val artifacts = root.optJSONArray("artifacts") ?: return null
            var apk: JSONObject? = null
            for (index in 0 until artifacts.length()) {
                val artifact = artifacts.optJSONObject(index) ?: continue
                if (!artifact.optString("name").endsWith(".apk")) continue
                val current = apk
                if (current == null || artifact.optString("arch") == "universal") {
                    apk = artifact
                }
            }
            val chosen = apk ?: return null
            val url = chosen.optString("url")
            if (url.isBlank()) return null
            return AppUpdate(
                versionCode = versionCode,
                versionName = root.optString("versionName", root.optString("version")),
                downloadUrl = url,
                sizeBytes = chosen.optLong("size", 0L),
                sha256 = chosen.optString("sha256"),
            )
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(
        context: Context,
        update: AppUpdate,
        onProgress: (Int) -> Unit,
    ): File? = withContext(Dispatchers.IO) {
        runCatching {
            val dir = File(context.cacheDir, "updates").apply { mkdirs() }
            val target = File(dir, "diplay-installer-${update.versionCode}.apk")
            if (isFileReady(target, update)) {
                onProgress(100)
                return@runCatching target
            }
            target.delete()

            val connection = (URL(update.downloadUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "DiPlayInstaller/0.1")
            }
            try {
                if (connection.responseCode !in 200..299) return@runCatching null
                val total = if (update.sizeBytes > 0L) update.sizeBytes else -1L
                val temp = File(dir, "${target.name}.part")
                connection.inputStream.use { input ->
                    FileOutputStream(temp).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        var downloaded = 0L
                        var lastReported = -1
                        while (true) {
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                            downloaded += read
                            if (total > 0) {
                                val percent = (downloaded * 100 / total).toInt()
                                if (percent != lastReported) {
                                    lastReported = percent
                                    onProgress(percent.coerceIn(0, 100))
                                }
                            }
                        }
                        output.flush()
                        output.fd.sync()
                    }
                }
                if (!isFileReady(temp, update)) {
                    temp.delete()
                    return@runCatching null
                }
                if (!temp.renameTo(target)) {
                    temp.copyTo(target, overwrite = true)
                    temp.delete()
                }
                target
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    private fun isFileReady(file: File, update: AppUpdate): Boolean {
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
}

fun installUpdateApk(context: Context, file: File) {
    val uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        file,
    )
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(uri, "application/vnd.android.package-archive")
        .addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or
                Intent.FLAG_ACTIVITY_NEW_TASK,
        )
    context.startActivity(intent)
}

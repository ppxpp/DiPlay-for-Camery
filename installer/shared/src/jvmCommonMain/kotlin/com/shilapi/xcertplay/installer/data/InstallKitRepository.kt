package com.shilapi.xcertplay.installer.data

import com.shilapi.xcertplay.installer.data.model.InstallKit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI

object InstallKitRepository {
    private const val KIT_URL =
        "https://diplay.mgulu.com/api/v1/install-kits/camry-a/stable/latest"

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun load(): Result<InstallKit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URI.create(KIT_URL).toURL().openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DiPlayInstaller/0.2")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("服务器返回 $code，暂时无法获取安装包信息")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                json.decodeFromString(InstallKit.serializer(), body)
            } finally {
                connection.disconnect()
            }
        }
    }
}

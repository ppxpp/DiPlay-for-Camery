package com.shilapi.xcertplay.installer.data

import com.shilapi.xcertplay.installer.data.model.DeviceMatch
import com.shilapi.xcertplay.installer.data.model.DeviceProfile
import com.shilapi.xcertplay.installer.data.model.InstallApp
import com.shilapi.xcertplay.installer.data.model.InstallFile
import com.shilapi.xcertplay.installer.data.model.InstallKit
import com.shilapi.xcertplay.installer.data.model.PostInstallAction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object InstallKitRepository {
    private const val KIT_URL =
        "https://diplay.mgulu.com/api/v1/install-kits/camry-a/stable/latest"

    suspend fun load(): Result<InstallKit> = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(KIT_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                instanceFollowRedirects = true
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", "DiPlayInstaller/0.1")
            }
            try {
                val code = connection.responseCode
                if (code !in 200..299) {
                    error("服务器返回 $code，暂时无法获取安装包信息")
                }
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                parse(JSONObject(body))
            } finally {
                connection.disconnect()
            }
        }
    }

    private fun parse(root: JSONObject): InstallKit {
        return InstallKit(
            schemaVersion = root.optInt("schemaVersion"),
            kitId = root.optString("kitId"),
            channel = root.optString("channel"),
            kitVersion = root.optString("kitVersion"),
            publishedAt = root.optString("publishedAt"),
            minToolVersion = root.optInt("minToolVersion"),
            deviceProfiles = root.optJSONArray("deviceProfiles").mapObjects(::parseDeviceProfile),
            apps = root.optJSONArray("apps").mapObjects(::parseApp).sortedBy { it.order },
            uninstallNotice = root.optString("uninstallNotice").takeIf { it.isNotBlank() },
        )
    }

    private fun parseDeviceProfile(json: JSONObject): DeviceProfile {
        val match = json.optJSONObject("match") ?: JSONObject()
        return DeviceProfile(
            id = json.optString("id"),
            name = json.optString("name"),
            match = DeviceMatch(
                modelRegex = match.optString("modelRegex"),
                fingerprintRegex = match.optString("fingerprintRegex"),
                sdkRange = match.optJSONArray("sdkRange").mapInts(),
            ),
        )
    }

    private fun parseApp(json: JSONObject): InstallApp {
        return InstallApp(
            id = json.optString("id"),
            label = json.optString("label"),
            replaces = json.optString("replaces").takeIf { it.isNotBlank() },
            packageName = json.optString("packageName"),
            versionName = json.optString("versionName"),
            versionCode = json.optLong("versionCode"),
            required = json.optBoolean("required"),
            order = json.optInt("order"),
            installMode = json.optString("installMode", "auto"),
            backupDefault = json.optBoolean("backupDefault", true),
            minSdk = json.optInt("minSdk", 0).takeIf { it > 0 },
            abis = json.optJSONArray("abis").mapStrings(),
            notes = json.optString("notes").takeIf { it.isNotBlank() },
            files = json.optJSONArray("files").mapObjects(::parseFile),
            postInstall = json.optJSONArray("postInstall").mapObjects(::parsePostInstall),
        )
    }

    private fun parseFile(json: JSONObject): InstallFile {
        return InstallFile(
            name = json.optString("name"),
            downloadUrl = json.optString("downloadUrl"),
            sizeBytes = json.optLong("sizeBytes"),
            sha256 = json.optString("sha256"),
        )
    }

    private fun parsePostInstall(json: JSONObject): PostInstallAction {
        return PostInstallAction(
            type = json.optString("type"),
            op = json.optString("op"),
            mode = json.optString("mode"),
        )
    }
}

private fun <T> JSONArray?.mapObjects(transform: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optJSONObject(index)?.let { add(transform(it)) }
        }
    }
}

private fun JSONArray?.mapStrings(): List<String> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            optString(index).takeIf { it.isNotBlank() }?.let(::add)
        }
    }
}

private fun JSONArray?.mapInts(): List<Int> {
    if (this == null) return emptyList()
    return buildList {
        for (index in 0 until length()) {
            add(optInt(index))
        }
    }
}

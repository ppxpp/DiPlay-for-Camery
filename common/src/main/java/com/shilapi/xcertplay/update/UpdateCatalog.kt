package com.shilapi.xcertplay.update

import org.json.JSONObject

/** The published release that a newer build can be downloaded from. */
internal data class UpdateRelease(
    val tagName: String,
    val versionCode: Long,
    val apkName: String,
    val apkUrl: String,
    val checksumsUrl: String,
)

internal object UpdateCatalog {
    internal const val CHECKSUMS_FILE = "SHA256SUMS.txt"

    /** Returns the first APK artifact from the latest manifest, or null. */
    internal fun parse(json: String): UpdateRelease? {
        val manifest = runCatching { JSONObject(json) }.getOrNull() ?: return null
        if (manifest.optInt("schemaVersion") != 1) return null
        val version = manifest.optString("version").trim()
        val versionCode = manifest.optLong("versionCode", -1L)
        if (version.isEmpty() || versionCode <= 0L) return null

        val artifacts = manifest.optJSONArray("artifacts") ?: return null
        var universal: JSONObject? = null
        var firstApk: JSONObject? = null
        for (index in 0 until artifacts.length()) {
            val artifact = artifacts.optJSONObject(index) ?: continue
            if (!artifact.optString("name").endsWith(".apk", ignoreCase = true)) continue
            if (firstApk == null) firstApk = artifact
            if (artifact.optString("arch").equals("universal", ignoreCase = true)) {
                universal = artifact
                break
            }
        }
        val artifact = universal ?: firstApk ?: return null

        val apkName = artifact.optString("name").trim()
        val apkUrl = artifact.optString("url").trim()
        val checksumsUrl = artifact.optString("checksumsUrl").trim()
        if (apkName.isEmpty() || apkUrl.isEmpty() || checksumsUrl.isEmpty()) return null
        if (!apkUrl.startsWith("https://") || !checksumsUrl.startsWith("https://")) return null

        return UpdateRelease(version, versionCode, apkName, apkUrl, checksumsUrl)
    }
}

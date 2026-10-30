package com.shilapi.xcertplay.installer.data.model

import kotlinx.serialization.Serializable

@Serializable
data class InstallKit(
    val schemaVersion: Int = 0,
    val kitId: String = "",
    val channel: String = "",
    val kitVersion: String = "",
    val publishedAt: String = "",
    val minToolVersion: Int = 0,
    val deviceProfiles: List<DeviceProfile> = emptyList(),
    val apps: List<InstallApp> = emptyList(),
    val uninstallNotice: String? = null,
) {
    val appCount: Int get() = apps.size

    val totalSizeBytes: Long
        get() = apps.flatMap { it.files }.sumOf { it.sizeBytes }

    val hasIncompleteFiles: Boolean
        get() = apps.any { app ->
            app.files.any { file -> file.sizeBytes <= 0L || file.sha256.isBlank() }
        }
}

@Serializable
data class DeviceProfile(
    val id: String = "",
    val name: String = "",
    val match: DeviceMatch = DeviceMatch(),
)

@Serializable
data class DeviceMatch(
    val modelRegex: String = "",
    val fingerprintRegex: String = "",
    val sdkRange: List<Int> = emptyList(),
)

@Serializable
data class InstallApp(
    val id: String = "",
    val label: String = "",
    val replaces: String? = null,
    val packageName: String = "",
    val versionName: String = "",
    val versionCode: Long = 0L,
    val required: Boolean = false,
    val order: Int = 0,
    val installMode: String = "auto",
    val backupDefault: Boolean = true,
    val minSdk: Int? = null,
    val abis: List<String> = emptyList(),
    val notes: String? = null,
    val files: List<InstallFile> = emptyList(),
    val postInstall: List<PostInstallAction> = emptyList(),
) {
    val replacesLabel: String
        get() = replaces ?: label

    val hasCompleteFile: Boolean
        get() = files.isNotEmpty() && files.all { it.sizeBytes > 0L && it.sha256.isNotBlank() }
}

@Serializable
data class InstallFile(
    val name: String = "",
    val downloadUrl: String = "",
    val sizeBytes: Long = 0L,
    val sha256: String = "",
)

@Serializable
data class PostInstallAction(
    val type: String = "",
    val op: String = "",
    val mode: String = "",
)

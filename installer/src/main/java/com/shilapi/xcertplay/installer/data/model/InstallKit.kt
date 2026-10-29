package com.shilapi.xcertplay.installer.data.model

data class InstallKit(
    val schemaVersion: Int,
    val kitId: String,
    val channel: String,
    val kitVersion: String,
    val publishedAt: String,
    val minToolVersion: Int,
    val deviceProfiles: List<DeviceProfile>,
    val apps: List<InstallApp>,
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

data class DeviceProfile(
    val id: String,
    val name: String,
    val match: DeviceMatch,
)

data class DeviceMatch(
    val modelRegex: String,
    val fingerprintRegex: String,
    val sdkRange: List<Int>,
)

data class InstallApp(
    val id: String,
    val label: String,
    val replaces: String? = null,
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val required: Boolean,
    val order: Int,
    val installMode: String,
    val backupDefault: Boolean,
    val minSdk: Int?,
    val abis: List<String>,
    val notes: String?,
    val files: List<InstallFile>,
    val postInstall: List<PostInstallAction>,
) {
    val replacesLabel: String
        get() = replaces ?: label

    val hasCompleteFile: Boolean
        get() = files.isNotEmpty() && files.all { it.sizeBytes > 0L && it.sha256.isNotBlank() }
}

data class InstallFile(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long,
    val sha256: String,
)

data class PostInstallAction(
    val type: String,
    val op: String,
    val mode: String,
)

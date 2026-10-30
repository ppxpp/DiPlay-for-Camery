package com.shilapi.xcertplay.installer.backup

import kotlinx.serialization.Serializable

@Serializable
data class BackupManifest(
    val schemaVersion: Int = 1,
    val createdAt: String = "",
    val device: BackupDevice = BackupDevice(),
    val kitId: String = "",
    val kitVersion: String = "",
    val apps: List<BackupAppManifest> = emptyList(),
)

@Serializable
data class BackupDevice(
    val model: String = "",
    val sdk: String = "",
)

@Serializable
data class BackupAppManifest(
    val packageName: String = "",
    val label: String = "",
    val versionCode: Long = 0L,
    val isSystemApp: Boolean = false,
    val files: List<BackupFileManifest> = emptyList(),
    val backedUpAt: String = "",
)

@Serializable
data class BackupFileManifest(
    val name: String = "",
    val remotePath: String = "",
    val sizeBytes: Long = 0L,
)

package com.shilapi.xcertplay.pioneer

import java.io.File
import java.io.IOException

/** Update cache only. Unconfirmed installer inputs remain protected, even under storage pressure. */
internal object PioneerUpdateCache {
    const val MAX_APKS = 4
    const val MAX_BYTES = 256L * 1024 * 1024
    const val FREE_RESERVE_BYTES = 32L * 1024 * 1024
    private const val INSTALL_RETENTION_MILLIS = 24L * 60 * 60 * 1000

    data class Cleanup(val reclaimedBytes: Long, val remainingBytes: Long, val protectedApks: Int, val failedDeletes: Int)

    fun isProtected(file: File, now: Long = System.currentTimeMillis()): Boolean {
        val marker = File(file.path + ".installing")
        // A clock adjustment must not prematurely expire an installer input.
        return marker.exists() && now - marker.lastModified() <= INSTALL_RETENTION_MILLIS
    }

    @Synchronized
    fun prune(directory: File, installed: (File) -> Boolean = { false }): Cleanup {
        var reclaimed = 0L
        var retained = 0
        var failed = 0
        apks(directory).forEach { file ->
            if (isProtected(file) && !runCatching { installed(file) }.getOrDefault(false)) {
                retained++
            } else {
                val bytes = file.length()
                if (file.delete()) {
                    reclaimed += bytes
                    if (File(file.path + ".installing").exists() && !File(file.path + ".installing").delete()) failed++
                } else failed++
            }
        }
        // A process death or OS cache eviction can leave markers without their APK.
        directory.listFiles().orEmpty().filter { it.name.endsWith(".apk.installing") && managed(directory, it) }
            .filter { !File(it.path.removeSuffix(".installing")).exists() }
            .forEach { if (!it.delete()) failed++ }
        return Cleanup(reclaimed, apks(directory).sumOf { it.length() }, retained, failed)
    }

    @Synchronized
    fun requireCapacity(directory: File, incoming: Long, available: Long) {
        val files = apks(directory)
        val bytes = files.sumOf { it.length() }
        if (files.size >= MAX_APKS || incoming > MAX_BYTES - bytes)
            throw IOException("APK cache limit reached; finish or cancel pending installations, then retry")
        if (available < incoming || available - incoming < FREE_RESERVE_BYTES)
            throw IOException("Insufficient storage for APK; free space before retrying")
    }

    /** A terminal installer result identifies exactly one input, never the current upload lease. */
    @Synchronized
    fun finish(directory: File, file: File): Boolean {
        require(safePath(directory, file) && (!file.exists() || file.isFile) && file.name.endsWith(".apk")) { "Invalid update cache path" }
        val removed = !file.exists() || file.delete()
        if (removed) File(file.path + ".installing").delete()
        return removed
    }

    private fun apks(directory: File) = directory.listFiles().orEmpty()
        .filter { it.name.endsWith(".apk") && managed(directory, it) }

    private fun managed(directory: File, file: File): Boolean = file.isFile && safePath(directory, file)

    private fun safePath(directory: File, file: File): Boolean =
        file.canonicalFile == File(directory.canonicalFile, file.name)
}

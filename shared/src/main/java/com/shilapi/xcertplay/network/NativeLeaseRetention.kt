package com.shilapi.xcertplay.network

import java.io.File

/**
 * Bounds the small journal directories left by native NCM leases. Only directories that the
 * caller has verified as safely completed are eligible; unconfirmed firewall state is retained.
 */
internal object NativeLeaseRetention {
    private const val MAX_COMPLETED_DIRECTORIES = 20
    private const val RETENTION_MILLIS = 7L * 24L * 60L * 60L * 1000L

    @Synchronized
    fun prune(
        root: File,
        now: Long = System.currentTimeMillis(),
        isSafelyCompleted: (File) -> Boolean,
    ): Int {
        val completed = root.listFiles().orEmpty()
            .filter { it.isDirectory && runCatching { isSafelyCompleted(it) }.getOrDefault(false) }
            .sortedByDescending(::lastActivity)
        var deleted = 0
        completed.forEachIndexed { index, directory ->
            val activity = lastActivity(directory)
            val expired = activity > 0L && now - activity > RETENTION_MILLIS
            if ((index >= MAX_COMPLETED_DIRECTORIES || expired) && directory.deleteRecursively()) {
                deleted += 1
            }
        }
        return deleted
    }

    private fun lastActivity(directory: File): Long =
        listOf("status.json", "request.json", "heartbeat", "firewall-journal.json")
            .map { File(directory, it) }
            .filter { it.isFile }
            .maxOfOrNull { it.lastModified() }
            ?.takeIf { it > 0L }
            ?: directory.lastModified()
}

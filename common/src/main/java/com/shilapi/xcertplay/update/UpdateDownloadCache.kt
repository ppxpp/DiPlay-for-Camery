package com.shilapi.xcertplay.update

import android.content.Context
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Process-scoped ownership for verified foreground update downloads. */
internal object UpdateDownloadCache {
    private val startupCleanup = AtomicBoolean()
    private val lock = Any()
    private var retained: File? = null

    fun begin(context: Context): File = synchronized(lock) {
        val root = root(context).apply { mkdirs() }
        removeHistoricalDirectories(root, retained)
        File(root, UUID.randomUUID().toString()).apply {
            check(mkdir()) { "Could not create update download directory" }
            retained = this
        }
    }

    fun retain(directory: File) = synchronized(lock) {
        retained = directory
    }

    fun release(directory: File, delete: Boolean) = synchronized(lock) {
        if (delete) directory.deleteRecursively()
        if (retained == directory) retained = null
    }

    fun cleanupForProcessStart(context: Context) {
        if (!startupCleanup.compareAndSet(false, true)) return
        synchronized(lock) {
            removeHistoricalDirectories(root(context), retained)
        }
    }

    internal fun removeHistoricalDirectories(root: File, keep: File?) {
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.canonicalFile != keep?.canonicalFile }
            .forEach(File::deleteRecursively)
    }

    private fun root(context: Context) = File(context.cacheDir, "update")
}

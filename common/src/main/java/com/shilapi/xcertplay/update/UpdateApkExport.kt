package com.shilapi.xcertplay.update

import android.content.ContentResolver
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException

/** Copies a verified APK where a file manager can reach it, for head units that cannot use the system installer. */
internal object UpdateApkExport {
    private const val APK_MIME = "application/vnd.android.package-archive"
    private val DOWNLOADS_DIRECTORY = "${Environment.DIRECTORY_DOWNLOADS}/DiPlay"

    /** [path] is shown to the driver. [uri] is set only for a MediaStore copy that file managers can open. */
    class Saved(val path: String, val uri: Uri?)

    /** Returns null when no public copy could be made. */
    fun copy(context: Context, apk: File): Saved? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                return copyToDownloads(context.contentResolver, apk)
            } catch (_: Exception) {
                // Fall through: some head units ship without a working Downloads provider.
            }
        }
        return try {
            val directory = File(context.getExternalFilesDir(null) ?: return null, "update")
            Saved(copyToDirectory(directory, apk).absolutePath, null)
        } catch (_: Exception) {
            null
        }
    }

    /** Remove historical exports while keeping the newest visible APK for manual recovery. */
    fun pruneHistory(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching { pruneMediaStoreHistory(context.contentResolver) }
        }
        context.getExternalFilesDir(null)?.let { base ->
            runCatching { pruneDirectoryHistory(File(base, "update")) }
        }
    }

    private fun copyToDirectory(directory: File, apk: File): File {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("Update storage is unavailable")
        // Keep only the newest APK so a long-lived head unit does not collect old releases.
        directory.listFiles()?.filter { it.isFile && it.name != apk.name }?.forEach { it.delete() }
        return apk.copyTo(File(directory, apk.name), overwrite = true)
    }

    internal fun pruneDirectoryHistory(directory: File) {
        if (!directory.isDirectory) return
        directory.listFiles().orEmpty()
            .filter { it.isFile && it.extension.equals("apk", ignoreCase = true) }
            .sortedByDescending(File::lastModified)
            .drop(1)
            .forEach(File::delete)
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun pruneMediaStoreHistory(resolver: ContentResolver) {
        val projection = arrayOf(
            MediaStore.Downloads._ID,
            MediaStore.Downloads.DATE_ADDED,
        )
        val rows = mutableListOf<Pair<Long, Long>>()
        resolver.query(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.MIME_TYPE}=?",
            arrayOf("$DOWNLOADS_DIRECTORY/", APK_MIME),
            "${MediaStore.Downloads.DATE_ADDED} DESC, ${MediaStore.Downloads._ID} DESC",
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads._ID)
            val dateColumn = cursor.getColumnIndexOrThrow(MediaStore.Downloads.DATE_ADDED)
            while (cursor.moveToNext()) rows += cursor.getLong(idColumn) to cursor.getLong(dateColumn)
        }
        rows.drop(1).forEach { (id, _) ->
            resolver.delete(
                android.content.ContentUris.withAppendedId(
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                    id,
                ),
                null,
                null,
            )
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun copyToDownloads(resolver: ContentResolver, apk: File): Saved {
        // Only entries created by this app are visible to these queries, so other apps' files are safe.
        resolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.RELATIVE_PATH}=? AND ${MediaStore.Downloads.MIME_TYPE}=?",
            arrayOf("$DOWNLOADS_DIRECTORY/", APK_MIME),
        )
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, apk.name)
            put(MediaStore.Downloads.MIME_TYPE, APK_MIME)
            put(MediaStore.Downloads.RELATIVE_PATH, DOWNLOADS_DIRECTORY)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: throw IOException("Downloads could not create the update")
        try {
            val output = resolver.openOutputStream(uri, "wt") ?: throw IOException("Update destination is unavailable")
            apk.inputStream().use { input -> output.use { input.copyTo(it) } }
            val published = resolver.update(uri, ContentValues().apply {
                put(MediaStore.Downloads.IS_PENDING, 0)
            }, null, null)
            if (published != 1) throw IOException("Downloads could not publish the update")
            // MediaStore renames on a name clash, so report the name it actually stored.
            val name = resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: apk.name
            return Saved("$DOWNLOADS_DIRECTORY/$name", uri)
        } catch (error: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw error
        }
    }
}

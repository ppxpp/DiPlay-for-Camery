package com.shilapi.xcertplay.update

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.SecureRandom

/** Installation ID: stable across updates, excluded from backups, reset by uninstall/data clearing. */
internal object StableClientId {
    private const val FILE_NAME = "diplay-update-client-v2"
    private val validId = Regex("[A-Za-z0-9_-]{10}")

    @Synchronized
    fun get(context: Context): String? = try {
        resolve(context).also { android.util.Log.i("DiPlayUpdate", "Update client ID: $it") }
    } catch (error: IOException) {
        android.util.Log.w("DiPlayUpdate", "Update client ID storage unavailable", error)
        null
    } catch (error: SecurityException) {
        android.util.Log.w("DiPlayUpdate", "Update client ID storage denied", error)
        null
    }

    private fun resolve(context: Context): String {
        // Some head-unit ROMs clone ANDROID_ID. Neither it nor the legacy preference is unique.
        val file = File(context.noBackupFilesDir, FILE_NAME)
        val store = AtomicFile(file)
        val saved = try {
            store.readFully().toString(Charsets.UTF_8)
        } catch (missing: FileNotFoundException) {
            if (file.exists()) throw missing
            null
        }
        if (saved != null && validId.matches(saved)) return saved

        val id = encode(ByteArray(7).also { SecureRandom().nextBytes(it) })
        val output = store.startWrite()
        try {
            output.write(id.toByteArray(Charsets.UTF_8))
            store.finishWrite(output)
        } catch (error: Exception) {
            store.failWrite(output)
            throw error
        }
        // Never send an ephemeral ID when the filesystem failed to retain it.
        if (store.readFully().toString(Charsets.UTF_8) != id) throw IOException("Client ID was not saved")
        return id
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

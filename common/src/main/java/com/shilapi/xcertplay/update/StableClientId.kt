package com.shilapi.xcertplay.update

import android.content.Context
import android.provider.Settings
import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/** Returns a 10-character app-scoped identifier that survives reinstall on the same Android user/device. */
internal object StableClientId {
    private const val PREFERENCES = "diplay_update_client_v1"
    private const val FALLBACK_ID = "fallback_id"

    fun get(context: Context): String? {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
        if (androidId != null) {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("com.shilapi.xcertplay:update-client-id:$androidId".toByteArray(Charsets.UTF_8))
                .copyOf(7)
            return encode(digest)
        }

        val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
        preferences.getString(FALLBACK_ID, null)?.let { return it }
        val generated = ByteArray(7).also { SecureRandom().nextBytes(it) }
        val id = encode(generated)
        preferences.edit().putString(FALLBACK_ID, id).commit()
        return id
    }

    private fun encode(bytes: ByteArray): String =
        Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
}

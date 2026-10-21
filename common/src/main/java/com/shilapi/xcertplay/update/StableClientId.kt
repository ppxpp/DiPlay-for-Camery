package com.shilapi.xcertplay.update

import android.content.Context
import android.provider.Settings
import java.security.MessageDigest
import android.util.Base64

/** Returns a short app-scoped identifier that survives reinstall on the same Android user/device. */
internal object StableClientId {
    fun get(context: Context): String? {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() }
            ?: return null
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("com.shilapi.xcertplay:update-client-id:$androidId".toByteArray(Charsets.UTF_8))
            .copyOf(16)
        return Base64.encodeToString(digest, Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING)
    }
}

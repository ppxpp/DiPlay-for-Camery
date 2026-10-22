package com.shilapi.xcertplay.update

import android.content.Context
import android.content.pm.PackageManager
import com.shilapi.xcertplay.host.R

/** A published update line. The selected channel is the only manifest the app checks. */
internal enum class UpdateChannel(val apiName: String, val labelRes: Int) {
    STABLE("stable", R.string.update_channel_stable),
    BETA("beta", R.string.update_channel_beta);

    companion object {
        fun fromApiName(value: String?): UpdateChannel? =
            entries.firstOrNull { it.apiName == value }
    }
}

/** A saved user choice wins; otherwise only an explicit stable build defaults to stable. */
internal object UpdateChannelStore {
    private const val PREFERENCES = "diplay_update_channel_v1"
    private const val CHANNEL = "channel"
    internal const val BUILD_CHANNEL_META_DATA = "com.shilapi.xcertplay.UPDATE_CHANNEL"

    fun load(context: Context): UpdateChannel =
        selected(context) ?: buildDefault(context)

    /** Null means the channel still follows the installed APK's build default. */
    fun selected(context: Context): UpdateChannel? =
        UpdateChannel.fromApiName(
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).getString(CHANNEL, null),
        )

    fun save(context: Context, channel: UpdateChannel) {
        check(context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .edit()
            .putString(CHANNEL, channel.apiName)
            .commit()) { "Could not save update channel" }
    }

    /**
     * The app manifest carries the channel this APK was built for. CI sets it to beta for
     * release-signed beta artifacts and to stable for official releases. A build without an
     * explicit stable marker is treated as beta so only the stable channel opts into stable.
     */
    private fun buildDefault(context: Context): UpdateChannel =
        UpdateChannel.fromApiName(
            runCatching {
                context.packageManager.getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
                    .metaData?.getString(BUILD_CHANNEL_META_DATA)
            }.getOrNull(),
        ) ?: UpdateChannel.BETA

    internal fun clearForTest(context: Context) {
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit().clear().commit()
    }
}

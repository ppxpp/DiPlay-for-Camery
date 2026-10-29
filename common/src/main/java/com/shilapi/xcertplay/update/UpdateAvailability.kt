package com.shilapi.xcertplay.update

import android.content.Context

/** Durable metadata only. APK downloads always remain an explicit foreground action. */
internal object UpdateAvailability {
    private const val PREFERENCES = "diplay_update_availability_v2"
    private const val TAG = "tag"
    private const val VERSION_CODE = "version_code"
    private const val APK_NAME = "apk_name"
    private const val APK_URL = "apk_url"
    private const val CHECKSUMS_URL = "checksums_url"
    private const val RELEASE_NOTES = "release_notes"
    private const val CHANNEL = "channel"
    private const val LAST_ATTEMPT_MILLIS = "last_attempt_millis"
    private const val BACKGROUND_CHECKS = "background_checks"
    private const val LAST_RESULT = "last_result"
    private const val LAST_RESULT_MILLIS = "last_result_millis"
    private const val LAST_INSTALL = "last_install"
    private const val LAST_INSTALL_MILLIS = "last_install_millis"

    internal const val CHECK_INTERVAL_MILLIS = 24L * 60 * 60 * 1000

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    fun backgroundChecksEnabled(context: Context): Boolean =
        preferences(context).getBoolean(BACKGROUND_CHECKS, true)

    fun saveBackgroundChecksEnabled(context: Context, enabled: Boolean) {
        preferences(context).edit().putBoolean(BACKGROUND_CHECKS, enabled).apply()
    }

    fun available(context: Context, installedVersionCode: Long, channel: UpdateChannel): UpdateRelease? {
        val values = preferences(context)
        val storedChannel = UpdateChannel.fromApiName(values.getString(CHANNEL, null))
        if (storedChannel != channel) {
            clear(context)
            return null
        }
        val release = UpdateRelease(
            tagName = values.getString(TAG, null) ?: return null,
            versionCode = values.getLong(VERSION_CODE, -1L),
            apkName = values.getString(APK_NAME, null) ?: return null,
            apkUrl = values.getString(APK_URL, null) ?: return null,
            checksumsUrl = values.getString(CHECKSUMS_URL, null) ?: return null,
            releaseNotes = values.getString(RELEASE_NOTES, null),
        )
        if (release.versionCode > 0L && UpdateVersion.isNewer(release.versionCode, installedVersionCode)) {
            return release
        }
        clear(context)
        return null
    }

    fun save(context: Context, release: UpdateRelease, channel: UpdateChannel) {
        preferences(context).edit()
            .putString(TAG, release.tagName)
            .putLong(VERSION_CODE, release.versionCode)
            .putString(APK_NAME, release.apkName)
            .putString(APK_URL, release.apkUrl)
            .putString(CHECKSUMS_URL, release.checksumsUrl)
            .putString(RELEASE_NOTES, release.releaseNotes)
            .putString(CHANNEL, channel.apiName)
            .apply()
    }

    fun clear(context: Context) {
        preferences(context).edit()
            .remove(TAG)
            .remove(VERSION_CODE)
            .remove(APK_NAME)
            .remove(APK_URL)
            .remove(CHECKSUMS_URL)
            .remove(RELEASE_NOTES)
            .remove(CHANNEL)
            .apply()
    }

    /** A channel switch must not inherit the previous channel's 24-hour check cadence. */
    fun resetForChannelChange(context: Context) {
        clear(context)
        preferences(context).edit().remove(LAST_ATTEMPT_MILLIS).apply()
    }

    fun shouldCheck(context: Context, nowMillis: Long): Boolean {
        val lastAttempt = preferences(context).getLong(LAST_ATTEMPT_MILLIS, 0L)
        return lastAttempt <= 0L || nowMillis < lastAttempt || nowMillis - lastAttempt >= CHECK_INTERVAL_MILLIS
    }

    fun recordAttempt(context: Context, nowMillis: Long) {
        preferences(context).edit().putLong(LAST_ATTEMPT_MILLIS, nowMillis).apply()
    }

    fun recordResult(context: Context, nowMillis: Long, result: String) {
        preferences(context).edit().putString(LAST_RESULT, result).putLong(LAST_RESULT_MILLIS, nowMillis).apply()
    }

    fun recordInstall(context: Context, nowMillis: Long, outcome: String) {
        preferences(context).edit().putString(LAST_INSTALL, outcome).putLong(LAST_INSTALL_MILLIS, nowMillis).apply()
    }

    /** One diagnostic line. Ages are relative so the report needs no clock or locale. */
    fun report(context: Context, nowMillis: Long): String {
        val values = preferences(context)
        fun age(key: String) = values.getLong(key, 0L).takeIf { it > 0L }
            ?.let { "${(nowMillis - it).coerceAtLeast(0L) / 60_000} min ago" } ?: "never"
        return "Update check: background=${if (backgroundChecksEnabled(context)) "on" else "off"}; " +
            "channel=${UpdateChannelStore.load(context).apiName}; " +
            "lastSuccess=${age(LAST_ATTEMPT_MILLIS)}; cachedRelease=${values.getString(TAG, null) ?: "none"}; " +
            "lastResult=${values.getString(LAST_RESULT, null) ?: "none"} (${age(LAST_RESULT_MILLIS)}); " +
            "lastInstall=${values.getString(LAST_INSTALL, null) ?: "none"} (${age(LAST_INSTALL_MILLIS)})"
    }

    internal fun clearAllForTest(context: Context) {
        preferences(context).edit().clear().commit()
        UpdateChannelStore.clearForTest(context)
    }
}

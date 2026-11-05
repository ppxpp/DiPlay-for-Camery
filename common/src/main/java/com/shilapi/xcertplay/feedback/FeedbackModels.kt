package com.shilapi.xcertplay.feedback

import org.json.JSONObject

internal enum class FeedbackCategory(val wireName: String) {
    CONNECTION("connection"),
    DISPLAY("display"),
    AUDIO("audio"),
    NAVIGATION("navigation"),
    VEHICLE("vehicle"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): FeedbackCategory? = entries.firstOrNull { it.wireName == value }
    }
}

internal enum class FeedbackEntry(val wireName: String) {
    SETTINGS("settings"),
    CONNECTION_FAILURE("connection_failure"),
    ;

    companion object {
        fun fromWire(value: String?): FeedbackEntry = entries.firstOrNull { it.wireName == value } ?: SETTINGS
    }
}

internal enum class FeedbackState {
    PENDING,
    UPLOADING,
    FAILED_RETRYABLE,
    FAILED_PERMANENT,
    ;

    companion object {
        fun fromWire(value: String?): FeedbackState = entries.firstOrNull { it.name == value } ?: PENDING
    }
}

internal data class FeedbackMeta(
    val id: String,
    val category: FeedbackCategory,
    val description: String,
    val entry: FeedbackEntry,
    val createdAt: Long,
    val failureStage: String? = null,
    val appVersionName: String = "unknown",
    val appVersionCode: Long = 0,
    val updateChannel: String = "unknown",
    val deviceManufacturer: String = "unknown",
    val deviceModel: String = "unknown",
    val deviceBoard: String = "unknown",
    val androidRelease: String = "unknown",
    val androidApi: Int = 0,
    val sessionMode: String = "unknown",
    val sessionState: String = "unknown",
    val bundleBytes: Long = 0,
    val bundleSha256: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("schemaVersion", 1)
        put("id", id)
        put("createdAt", createdAt)
        put("category", category.wireName)
        put("description", description)
        put("entry", entry.wireName)
        failureStage?.let { put("failureStage", it) }
        put("app", JSONObject().apply {
            put("versionName", appVersionName)
            put("versionCode", appVersionCode)
            put("channel", updateChannel)
        })
        put("device", JSONObject().apply {
            put("manufacturer", deviceManufacturer)
            put("model", deviceModel)
            put("board", deviceBoard)
            put("androidRelease", androidRelease)
            put("api", androidApi)
        })
        put("session", JSONObject().apply {
            put("mode", sessionMode)
            put("state", sessionState)
        })
        put("bundleBytes", bundleBytes)
        put("bundleSha256", bundleSha256)
    }

    companion object {
        fun fromJson(json: JSONObject): FeedbackMeta? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val category = FeedbackCategory.fromWire(json.optString("category")) ?: return null
            val app = json.optJSONObject("app") ?: JSONObject()
            val device = json.optJSONObject("device") ?: JSONObject()
            val session = json.optJSONObject("session") ?: JSONObject()
            return FeedbackMeta(
                id = id,
                category = category,
                description = json.optString("description").take(MAX_DESCRIPTION_CHARS),
                entry = FeedbackEntry.fromWire(json.optString("entry")),
                createdAt = json.optLong("createdAt"),
                failureStage = json.optString("failureStage").takeIf { it.isNotBlank() }?.take(500),
                appVersionName = app.optString("versionName", "unknown").take(128),
                appVersionCode = app.optLong("versionCode"),
                updateChannel = app.optString("channel", "unknown").take(32),
                deviceManufacturer = device.optString("manufacturer", "unknown").take(128),
                deviceModel = device.optString("model", "unknown").take(128),
                deviceBoard = device.optString("board", "unknown").take(128),
                androidRelease = device.optString("androidRelease", "unknown").take(64),
                androidApi = device.optInt("api"),
                sessionMode = session.optString("mode", "unknown").take(32),
                sessionState = session.optString("state", "unknown").take(32),
                bundleBytes = json.optLong("bundleBytes"),
                bundleSha256 = json.optString("bundleSha256").take(128),
            )
        }
    }
}

internal data class FeedbackRecord(
    val meta: FeedbackMeta,
    val state: FeedbackState,
    val attempts: Int,
    val lastError: String?,
    val lastAttemptAt: Long,
    val userInitiated: Boolean,
) {
    fun stateJson(): JSONObject = JSONObject().apply {
        put("state", state.name)
        put("attempts", attempts)
        lastError?.let { put("lastError", it.take(500)) }
        put("lastAttemptAt", lastAttemptAt)
        put("userInitiated", userInitiated)
    }

    companion object {
        fun fromJson(meta: FeedbackMeta, json: JSONObject): FeedbackRecord = FeedbackRecord(
            meta = meta,
            state = FeedbackState.fromWire(json.optString("state")),
            attempts = json.optInt("attempts"),
            lastError = json.optString("lastError").takeIf { it.isNotBlank() },
            lastAttemptAt = json.optLong("lastAttemptAt"),
            userInitiated = json.optBoolean("userInitiated"),
        )
    }
}

internal data class SentFeedback(
    val id: String,
    val category: FeedbackCategory,
    val createdAt: Long,
    val sentAt: Long,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("category", category.wireName)
        put("createdAt", createdAt)
        put("sentAt", sentAt)
    }

    companion object {
        fun fromJson(json: JSONObject): SentFeedback? {
            val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
            val category = FeedbackCategory.fromWire(json.optString("category")) ?: FeedbackCategory.OTHER
            return SentFeedback(id, category, json.optLong("createdAt"), json.optLong("sentAt"))
        }
    }
}

internal const val MAX_DESCRIPTION_CHARS = 2000
internal const val MAX_APP_VERSION_CHARS = 128

internal class FeedbackQueueFullException : IllegalStateException("The feedback queue is full")

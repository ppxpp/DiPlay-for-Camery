package com.shilapi.xcertplay.feedback

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * File-backed feedback queue. Every record is one directory so a killed process never leaves a
 * half-written bundle paired with a complete state file.
 */
internal object FeedbackOutbox {
    private const val OUTBOX_NAME = "feedback-outbox"
    private const val SENT_NAME = "feedback-sent.json"
    private const val BUNDLE_NAME = "bundle.zip"
    private const val META_NAME = "meta.json"
    private const val STATE_NAME = "state.json"
    internal const val MAX_PENDING = 5
    internal const val MAX_PERMANENT = 5
    internal const val MAX_SENT = 10
    private const val PERMANENT_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    private const val SENT_RETENTION_MS = 30L * 24 * 60 * 60 * 1000

    @Synchronized
    fun enqueue(context: Context, meta: FeedbackMeta, bundle: File): FeedbackRecord {
        cleanup(context)
        if (pending(context).size >= MAX_PENDING) throw FeedbackQueueFullException()
        val directory = recordDirectory(context, meta.id)
        if (directory.exists()) directory.deleteRecursively()
        check(directory.mkdirs()) { "Could not create the feedback record directory" }
        val destination = File(directory, BUNDLE_NAME)
        if (!bundle.renameTo(destination)) {
            bundle.copyTo(destination, overwrite = true)
            bundle.delete()
        }
        writeJson(File(directory, META_NAME), meta.toJson())
        val record = FeedbackRecord(
            meta = meta,
            state = FeedbackState.PENDING,
            attempts = 0,
            lastError = null,
            lastAttemptAt = 0,
            userInitiated = true,
        )
        writeJson(File(directory, STATE_NAME), record.stateJson())
        return record
    }

    /** Pending, uploading and auto-retry records; permanent failures are separate. */
    @Synchronized
    fun pending(context: Context): List<FeedbackRecord> = records(context)
        .filter { it.state == FeedbackState.PENDING || it.state == FeedbackState.UPLOADING || it.state == FeedbackState.FAILED_RETRYABLE }
        .sortedBy { it.meta.createdAt }

    @Synchronized
    fun permanentFailures(context: Context): List<FeedbackRecord> = records(context)
        .filter { it.state == FeedbackState.FAILED_PERMANENT }
        .sortedByDescending { it.meta.createdAt }

    @Synchronized
    fun records(context: Context): List<FeedbackRecord> {
        cleanup(context)
        return root(context).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull(::readRecord)
            ?.sortedBy { it.meta.createdAt }
            .orEmpty()
    }

    @Synchronized
    fun record(context: Context, id: String): FeedbackRecord? = readRecord(recordDirectory(context, id))

    @Synchronized
    fun pendingCount(context: Context): Int = pending(context).size + permanentFailures(context).size

    fun bundle(context: Context, id: String): File = File(recordDirectory(context, id), BUNDLE_NAME)

    @Synchronized
    fun markUploading(context: Context, id: String) {
        updateState(context, id) { state ->
            state.put("state", FeedbackState.UPLOADING.name)
            state.put("attempts", state.optInt("attempts") + 1)
            state.put("lastAttemptAt", System.currentTimeMillis())
        }
    }

    @Synchronized
    fun markRetryable(context: Context, id: String, reason: String) {
        updateState(context, id) { state ->
            state.put("state", FeedbackState.FAILED_RETRYABLE.name)
            state.put("lastError", reason.take(500))
            state.put("lastAttemptAt", System.currentTimeMillis())
            // The first attempt was user initiated; later automatic retries must yield to CarPlay.
            state.put("userInitiated", false)
        }
    }

    @Synchronized
    fun markPermanent(context: Context, id: String, reason: String) {
        updateState(context, id) { state ->
            state.put("state", FeedbackState.FAILED_PERMANENT.name)
            state.put("lastError", reason.take(500))
            state.put("lastAttemptAt", System.currentTimeMillis())
            state.put("userInitiated", false)
        }
        cleanup(context)
    }

    @Synchronized
    fun markSent(context: Context, id: String) {
        val record = readRecord(recordDirectory(context, id)) ?: return
        val sent = sentHistory(context).filterNot { it.id == id } + SentFeedback(
            id = record.meta.id,
            category = record.meta.category,
            createdAt = record.meta.createdAt,
            sentAt = System.currentTimeMillis(),
        )
        writeSent(context, sent)
        recordDirectory(context, id).deleteRecursively()
    }

    @Synchronized
    fun delete(context: Context, id: String) {
        recordDirectory(context, id).deleteRecursively()
    }

    @Synchronized
    fun sentHistory(context: Context): List<SentFeedback> {
        val now = System.currentTimeMillis()
        return readSent(context)
            .filter { now - it.sentAt <= SENT_RETENTION_MS }
            .sortedByDescending { it.sentAt }
            .take(MAX_SENT)
    }

    @Synchronized
    fun deleteSent(context: Context, id: String) {
        writeSent(context, sentHistory(context).filterNot { it.id == id })
    }

    @Synchronized
    fun clearSent(context: Context) {
        sentFile(context).delete()
    }

    @Synchronized
    fun cleanup(context: Context) {
        val root = root(context)
        val now = System.currentTimeMillis()
        root.listFiles()?.filter { it.isDirectory && it.name.endsWith(".tmp") }?.forEach { it.deleteRecursively() }
        val permanent = root.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull(::readRecord)
            ?.filter { it.state == FeedbackState.FAILED_PERMANENT }
            ?.sortedByDescending { it.meta.createdAt }
            .orEmpty()
        permanent.forEachIndexed { index, record ->
            val failedAt = record.lastAttemptAt.takeIf { it > 0 } ?: record.meta.createdAt
            val expired = now - failedAt > PERMANENT_RETENTION_MS
            if (index >= MAX_PERMANENT || expired) recordDirectory(context, record.meta.id).deleteRecursively()
        }
        val sent = readSent(context)
        val kept = sent
            .filter { now - it.sentAt <= SENT_RETENTION_MS }
            .sortedByDescending { it.sentAt }
            .take(MAX_SENT)
        if (kept != sent) writeSent(context, kept)
    }

    private fun updateState(context: Context, id: String, transform: (JSONObject) -> Unit) {
        val file = File(recordDirectory(context, id), STATE_NAME)
        if (!file.isFile) return
        val state = readJson(file) ?: JSONObject()
        transform(state)
        writeJson(file, state)
    }

    private fun readRecord(directory: File): FeedbackRecord? {
        if (!directory.isDirectory) return null
        val meta = readJson(File(directory, META_NAME))?.let(FeedbackMeta::fromJson) ?: return null
        val state = readJson(File(directory, STATE_NAME)) ?: JSONObject()
        return FeedbackRecord.fromJson(meta, state)
    }

    private fun readSent(context: Context): List<SentFeedback> {
        val file = sentFile(context)
        if (!file.isFile) return emptyList()
        val root = runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull() ?: return emptyList()
        val array = root.optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(SentFeedback::fromJson)
        }
    }

    private fun writeSent(context: Context, records: List<SentFeedback>) {
        val array = JSONArray()
        records.sortedByDescending { it.sentAt }.take(MAX_SENT).forEach { array.put(it.toJson()) }
        writeJson(sentFile(context), JSONObject().put("schemaVersion", 1).put("items", array))
    }

    private fun readJson(file: File): JSONObject? {
        if (!file.isFile) return null
        return runCatching { JSONObject(file.readText(Charsets.UTF_8)) }.getOrNull()
    }

    private fun writeJson(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.toString(), Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(json.toString(), Charsets.UTF_8)
            temp.delete()
        }
    }

    private fun root(context: Context): File =
        File(context.applicationContext.filesDir, OUTBOX_NAME).apply { if (!isDirectory) mkdirs() }

    private fun recordDirectory(context: Context, id: String): File = File(root(context), id)

    private fun sentFile(context: Context): File = File(context.applicationContext.filesDir, SENT_NAME)
}

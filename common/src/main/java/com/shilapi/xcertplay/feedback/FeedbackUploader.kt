package com.shilapi.xcertplay.feedback

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLConnection
import java.util.UUID

internal object FeedbackUploader {
    private const val ENDPOINT = "https://diplay.mgulu.com/api/v1/apps/diplay/feedback"
    internal const val MAX_BUNDLE_BYTES = 5L * 1024 * 1024
    private const val CONNECT_TIMEOUT_MILLIS = 10_000
    private const val READ_TIMEOUT_MILLIS = 120_000
    private const val RESPONSE_LIMIT = 64 * 1024

    sealed interface Result {
        data class Sent(val id: String) : Result
        data class Retryable(val reason: String) : Result
        data class Permanent(val reason: String) : Result
    }

    internal fun upload(
        context: Context,
        meta: FeedbackMeta,
        bundle: File,
        endpoint: URL = URL(ENDPOINT),
        openConnection: (URL) -> URLConnection? = { FeedbackConnectivity.openConnection(context, it) },
    ): Result {
        if (!bundle.isFile) return Result.Permanent("bundle_missing")
        if (bundle.length() > MAX_BUNDLE_BYTES) return Result.Permanent("bundle_too_large")
        val payloadMeta = meta.copy(
            bundleBytes = bundle.length(),
            bundleSha256 = FeedbackBundle.sha256(bundle),
        ).toJson().toString()
        val boundary = "----DiPlayFeedback${UUID.randomUUID()}"
        val metaHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"meta\"\r\n" +
            "Content-Type: application/json; charset=utf-8\r\n\r\n"
        val bundleHeader = "--$boundary\r\n" +
            "Content-Disposition: form-data; name=\"bundle\"; filename=\"diplay-feedback-${meta.id}.zip\"\r\n" +
            "Content-Type: application/zip\r\n\r\n"
        val closing = "\r\n--$boundary--\r\n"
        val total = metaHeader.toByteArray(Charsets.UTF_8).size +
            payloadMeta.toByteArray(Charsets.UTF_8).size +
            bundleHeader.toByteArray(Charsets.UTF_8).size +
            bundle.length() +
            closing.toByteArray(Charsets.UTF_8).size

        val connection = try {
            openConnection(endpoint) ?: return Result.Retryable("no_network")
        } catch (error: Exception) {
            return Result.Retryable(error.javaClass.simpleName)
        }
        val http = connection as? HttpURLConnection ?: return Result.Retryable("not_http")
        try {
            http.requestMethod = "POST"
            http.connectTimeout = CONNECT_TIMEOUT_MILLIS
            http.readTimeout = READ_TIMEOUT_MILLIS
            http.useCaches = false
            http.doOutput = true
            http.setFixedLengthStreamingMode(total)
            http.setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            http.setRequestProperty("Accept", "application/json")
            http.setRequestProperty("User-Agent", "DiPlay/${meta.appVersionName}")
            http.setRequestProperty("X-DiPlay-Feedback-Id", meta.id)
            http.outputStream.buffered().use { output ->
                output.write(metaHeader.toByteArray(Charsets.UTF_8))
                output.write(payloadMeta.toByteArray(Charsets.UTF_8))
                output.write(bundleHeader.toByteArray(Charsets.UTF_8))
                bundle.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
                output.write(closing.toByteArray(Charsets.UTF_8))
            }
            val status = http.responseCode
            val stream = if (status in 200..299) http.inputStream else http.errorStream
            val body = stream?.bufferedReader(Charsets.UTF_8)?.use { reader ->
                val buffer = CharArray(RESPONSE_LIMIT)
                val count = reader.read(buffer)
                if (count <= 0) "" else String(buffer, 0, count)
            }.orEmpty()
            return when {
                status in 200..299 -> {
                    val id = runCatching { JSONObject(body).optString("id") }.getOrNull()
                    Result.Sent(id?.takeIf { it.isNotBlank() } ?: meta.id)
                }
                status == 408 || status == 429 || status >= 500 -> Result.Retryable("http_$status")
                else -> Result.Permanent("http_$status ${body.take(200)}")
            }
        } catch (error: Exception) {
            return Result.Retryable(error.javaClass.simpleName)
        } finally {
            runCatching { http.disconnect() }
        }
    }
}

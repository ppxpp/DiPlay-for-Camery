package com.shilapi.xcertplay.feedback

import android.content.Context
import com.shilapi.xcertplay.DiagnosticArchive
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.SessionLogFile
import com.shilapi.xcertplay.WheelKeyLog
import com.shilapi.xcertplay.WheelKeyService
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Builds the small, redacted archive uploaded by the feedback screen. */
internal object FeedbackBundle {
    internal const val MAX_BYTES = 5L * 1024 * 1024
    private const val ARCHIVE_CONTENT_LIMIT = 4_800_000L
    private const val ENTRY_LIMIT = 2_000_000
    private const val REPORT_LIMIT = 1_500_000
    private const val LOG_LIMIT_EACH = 512 * 1024
    private const val LOG_LIMIT_TOTAL = 1_500_000
    private const val WHEEL_LIMIT = 128 * 1024
    private const val CRASH_LIMIT = 256 * 1024
    private const val LOGCAT_LIMIT = 512 * 1024

    fun build(context: Context, meta: FeedbackMeta, output: File): File {
        val app = context.applicationContext
        output.parentFile?.mkdirs()
        output.delete()
        val sensitive = sensitiveLiterals(app)
        val report = FeedbackReport.build(app, meta).take(REPORT_LIMIT)
        val logcat = FeedbackLogcat.capture()
        val feedbackJson = meta.copy(bundleBytes = 0, bundleSha256 = "").toJson().toString(2)
        val crash = File(app.filesDir, "logs/last-crash.txt")
            .takeIf { it.isFile }
            ?.let { readLimited(it, CRASH_LIMIT) }
        val wheel = WheelKeyLog.report(WheelKeyService.connected()).joinToString("\n").take(WHEEL_LIMIT)

        DiagnosticArchive(
            output,
            textLimit = ENTRY_LIMIT,
            totalLimit = ARCHIVE_CONTENT_LIMIT,
        ).use { archive ->
            archive.raw("feedback.json", scrub(feedbackJson, sensitive).toByteArray(Charsets.UTF_8))
            archive.raw("README.txt", README.toByteArray(Charsets.UTF_8))
            archive.text("report.txt") { report }
            crash?.let { archive.text("crash/last-crash.txt") { it } }
            sessionLogs(app).forEach { (name, text) ->
                archive.text("logs/$name") { text }
            }
            if (wheel.isNotBlank()) archive.text("logs/wheel-keys.txt") { wheel }
            logcat.text?.let { archive.text("logcat/logcat.txt", logcat.status) { it } }
                ?: archive.unavailable("logcat/logcat.txt", logcat.status, logcat.reason ?: "unavailable")
        }

        verify(output)
        check(output.length() <= MAX_BYTES) { "The feedback bundle is too large" }
        return output
    }

    fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun sessionLogs(context: Context): List<Pair<String, String>> {
        val logs = mutableListOf<Pair<String, String>>()
        var total = 0
        for (name in SessionLogFile.REPORT_NAMES.takeLast(3).reversed()) {
            val file = File(context.filesDir, "logs/$name")
            if (!file.isFile) continue
            val remaining = LOG_LIMIT_TOTAL - total
            if (remaining <= 0) break
            val text = readLimited(file, minOf(LOG_LIMIT_EACH, remaining))
            if (text.isBlank()) continue
            logs += name to text
            total += text.toByteArray(Charsets.UTF_8).size
        }
        return logs
    }

    private fun sensitiveLiterals(context: Context): List<String> = buildList {
        val name = DiPlayPreferences.phoneName(context)
        if (name.isNotBlank() && name != "Your iPhone" && name.length >= 3) add(name)
        DiPlayPreferences.phoneAddress(context)?.takeIf { it.length >= 3 }?.let(::add)
    }

    private fun scrub(text: String, sensitive: List<String>): String {
        var result = text
        sensitive.forEach { literal -> result = result.replace(literal, "[device]") }
        return result
    }

    private fun readLimited(file: File, limit: Int): String {
        val bytes = file.inputStream().use { input ->
            val buffer = ByteArray(minOf(limit, 64 * 1024).coerceAtLeast(1))
            val output = java.io.ByteArrayOutputStream()
            var remaining = limit
            while (remaining > 0) {
                val count = input.read(buffer, 0, minOf(buffer.size, remaining))
                if (count < 0) break
                output.write(buffer, 0, count)
                remaining -= count
            }
            output.toByteArray()
        }
        val text = String(bytes, Charsets.UTF_8)
        return if (file.length() > limit) text + "\n[truncated]\n" else text
    }

    private fun verify(file: File) {
        ZipFile(file).use { zip ->
            val required = listOf("feedback.json", "report.txt", "manifest.json", "README.txt")
            required.forEach { path -> check(zip.getEntry(path) != null) { "Missing $path" } }
            zip.entries().asSequence().forEach { entry ->
                zip.getInputStream(entry).use { it.copyTo(NullOutput, 64 * 1024) }
            }
        }
    }

    private object NullOutput : OutputStream() {
        override fun write(value: Int) = Unit
        override fun write(bytes: ByteArray, offset: Int, length: Int) = Unit
    }

    private const val README = """DiPlay anonymous feedback bundle

This archive contains a redacted diagnostic summary, bounded DiPlay session logs, and a crash
record when one exists. It does not intentionally include pairing keys, hotspot passwords,
protocol payloads, system code, installed-package inventories, phone names, Bluetooth addresses,
SSIDs or IP addresses.

The description in feedback.json is written by the person sending the feedback. Log entries are
redacted before they are added to the archive. The matching that replaces the phone name and
address runs before the archive writer.

The server keeps the bundle for 90 days. The administrator can delete it earlier.
"""
}

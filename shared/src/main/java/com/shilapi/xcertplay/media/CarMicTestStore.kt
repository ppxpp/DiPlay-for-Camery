package com.shilapi.xcertplay.media

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** One completed car-microphone test, stored only in the app's private directory. */
data class CarMicTestRecording(
    val file: File,
    val createdAt: Long,
    val durationMs: Long,
    val sampleRate: Int,
    val channels: Int,
    val peak: Int,
    val rmsDbfs: Double?,
    val verdict: String,
    val source: String,
    val gate: String,
    val routedDeviceType: Int?,
) {
    val displayTime: String
        get() = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(createdAt))
}

/** Bounded WAV history for the manual microphone test; recordings never leave app storage. */
object CarMicTestStore {
    const val MAX_RECORDINGS = 20

    private const val STALE_PART_MILLIS = 60 * 60 * 1000L

    fun directory(context: Context): File =
        File(context.filesDir, "mic-tests").apply { mkdirs() }

    fun newPart(context: Context): File {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.US).format(Date())
        return File(directory(context), "mic-$stamp.wav.part")
    }

    fun completedFile(part: File): File = File(part.parentFile, part.name.removeSuffix(".part"))

    fun saveMetadata(
        context: Context,
        audio: File,
        createdAt: Long,
        durationMs: Long,
        sampleRate: Int,
        channels: Int,
        peak: Int,
        rmsDbfs: Double?,
        verdict: String,
        verdictKey: String,
        source: String,
        gate: String,
        routedDeviceType: Int?,
        diagnostics: JSONObject? = null,
    ): Boolean {
        val json = JSONObject()
            .put("createdAt", createdAt)
            .put("durationMs", durationMs)
            .put("sampleRate", sampleRate)
            .put("channels", channels)
            .put("peak", peak)
            .put("peakDbfs", peakDbfs(peak) ?: JSONObject.NULL)
            .put("rmsDbfs", rmsDbfs?.takeIf { it.isFinite() } ?: JSONObject.NULL)
            .put("verdict", verdict)
            .put("verdictKey", verdictKey)
            .put("source", source)
            .put("gate", gate)
            .put("routedDeviceType", routedDeviceType ?: JSONObject.NULL)
            .put("format", "wav-pcm16")
            .put("diagnostics", diagnostics ?: JSONObject.NULL)
        val saved = runCatching { sidecarFor(audio).writeText(json.toString()) }.isSuccess
        prune(context)
        return saved
    }

    fun recordings(context: Context): List<CarMicTestRecording> {
        val dir = directory(context)
        removeStaleParts(dir)
        return (dir.listFiles() ?: emptyArray())
            .asSequence()
            .filter { it.isFile && it.name.endsWith(".wav") }
            .mapNotNull(::readRecording)
            .sortedByDescending { it.createdAt }
            .toList()
    }

    fun clearAll(context: Context): Int {
        val files = directory(context).listFiles() ?: return 0
        val count = files.count { it.name.endsWith(".wav") }
        files.forEach { runCatching { it.delete() } }
        return count
    }

    private fun prune(context: Context) {
        recordings(context).drop(MAX_RECORDINGS).forEach { recording ->
            runCatching { recording.file.delete() }
            runCatching { sidecarFor(recording.file).delete() }
        }
    }

    private fun removeStaleParts(directory: File) {
        val cutoff = System.currentTimeMillis() - STALE_PART_MILLIS
        directory.listFiles()
            ?.filter { it.name.endsWith(".part") && it.lastModified() < cutoff }
            ?.forEach { runCatching { it.delete() } }
    }

    private fun readRecording(file: File): CarMicTestRecording? {
        val info = WavPcm16.read(file) ?: return null
        val metadata = runCatching { JSONObject(sidecarFor(file).readText()) }.getOrNull()
        val sampleRate = metadata?.optInt("sampleRate", 0)?.takeIf { it > 0 } ?: info.sampleRate
        val channels = metadata?.optInt("channels", 0)?.takeIf { it > 0 } ?: info.channels
        val durationMs = metadata?.optLong("durationMs", 0L)?.takeIf { it > 0 }
            ?: info.dataBytes * 1000L / (sampleRate.toLong() * channels * 2)
        val createdAt = metadata?.optLong("createdAt", 0L)?.takeIf { it > 0 } ?: file.lastModified()
        val rms = metadata?.optDouble("rmsDbfs", Double.NaN)?.takeIf { it.isFinite() }
        val routed = metadata?.optInt("routedDeviceType", -1)?.takeIf { it >= 0 }
        return CarMicTestRecording(
            file = file,
            createdAt = createdAt,
            durationMs = durationMs,
            sampleRate = sampleRate,
            channels = channels,
            peak = metadata?.optInt("peak", 0) ?: 0,
            rmsDbfs = rms,
            verdict = metadata?.optString("verdict", "未分析").orEmpty().ifBlank { "未分析" },
            source = metadata?.optString("source", "MIC").orEmpty().ifBlank { "MIC" },
            gate = metadata?.optString("gate", "unknown").orEmpty().ifBlank { "unknown" },
            routedDeviceType = routed,
        )
    }

    private fun sidecarFor(audio: File): File =
        File(audio.parentFile, audio.name.removeSuffix(".wav") + ".json")

    private fun peakDbfs(peak: Int): Double? =
        if (peak <= 0) null else 20 * kotlin.math.log10(peak / 32768.0)
}

/** Minimal PCM16 WAV writer/reader used by the local test recordings. */
internal object WavPcm16 {
    const val HEADER_BYTES = 44

    data class Info(val sampleRate: Int, val channels: Int, val dataOffset: Long, val dataBytes: Long)

    fun writeHeader(output: RandomAccessFile, sampleRate: Int, channels: Int, dataBytes: Long) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channels in 1..2) { "only mono and stereo are supported" }
        require(dataBytes >= 0) { "dataBytes must not be negative" }
        output.seek(0)
        output.writeBytes("RIFF")
        writeLe32(output, (36L + dataBytes).coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
        output.writeBytes("WAVE")
        output.writeBytes("fmt ")
        writeLe32(output, 16)
        writeLe16(output, 1) // PCM
        writeLe16(output, channels)
        writeLe32(output, sampleRate)
        writeLe32(output, sampleRate * channels * 2)
        writeLe16(output, channels * 2)
        writeLe16(output, 16)
        output.writeBytes("data")
        writeLe32(output, dataBytes.coerceAtMost(Int.MAX_VALUE.toLong()).toInt())
    }

    fun read(file: File): Info? = runCatching { readUnchecked(file) }.getOrNull()

    private fun readUnchecked(file: File): Info? {
        if (file.length() < HEADER_BYTES) return null
        RandomAccessFile(file, "r").use { input ->
            val riff = ByteArray(12)
            input.readFully(riff)
            if (String(riff, 0, 4, Charsets.US_ASCII) != "RIFF") return null
            if (String(riff, 8, 4, Charsets.US_ASCII) != "WAVE") return null
            var format = 0
            var channels = 0
            var sampleRate = 0
            var bits = 0
            var position = 12L
            while (position + 8 <= input.length()) {
                input.seek(position)
                val idBytes = ByteArray(4)
                input.readFully(idBytes)
                val id = String(idBytes, Charsets.US_ASCII)
                val size = readLe32(input).toLong() and 0xFFFFFFFFL
                val body = position + 8
                if (id == "fmt ") {
                    if (size < 16) return null
                    input.seek(body)
                    format = readLe16(input)
                    channels = readLe16(input)
                    sampleRate = readLe32(input)
                    readLe32(input)
                    readLe16(input)
                    bits = readLe16(input)
                } else if (id == "data") {
                    if (format != 1 || bits != 16 || channels <= 0 || sampleRate <= 0) return null
                    val bytes = minOf(size, input.length() - body).coerceAtLeast(0)
                    return Info(sampleRate, channels, body, bytes)
                }
                position = body + size + (size and 1L)
            }
            return null
        }
    }

    private fun writeLe16(output: RandomAccessFile, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
    }

    private fun writeLe32(output: RandomAccessFile, value: Int) {
        output.write(value and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 24) and 0xFF)
    }

    private fun readLe16(input: RandomAccessFile): Int {
        val low = input.read()
        val high = input.read()
        return if (low < 0 || high < 0) -1 else low or (high shl 8)
    }

    private fun readLe32(input: RandomAccessFile): Int {
        val a = input.read()
        val b = input.read()
        val c = input.read()
        val d = input.read()
        if (a < 0 || b < 0 || c < 0 || d < 0) return -1
        return a or (b shl 8) or (c shl 16) or (d shl 24)
    }
}

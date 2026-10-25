package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.SystemClock
import com.shilapi.xcertplay.pioneer.PioneerUsbPolicy
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Bounded manual capture through the same Pioneer microphone gate used by CarPlay uplink.
 * PCM is written to app-private WAV history; only numeric diagnostics are logged.
 */
object CarMicTestRecorder {
    const val SAMPLE_RATE = 16_000
    const val DEFAULT_SECONDS = 5

    private const val READ_SAMPLES = 640
    private const val READ_EMPTY_SLEEP_MILLIS = 10L
    private const val LIVE_UPDATE_MILLIS = 100L

    private val lock = Any()

    @Volatile private var worker: Thread? = null
    @Volatile private var stopRequested = false
    @Volatile private var state: JSONObject = JSONObject().put("state", "idle")

    fun isRunning(): Boolean = worker != null

    /** Load/bind the provisioned PAL SDK before asking the driver to speak; no microphone acquired. */
    fun preparePal(context: Context) {
        check(PioneerUsbPolicy.matchesDevice()) { "PAL 仅支持已验证先锋车机" }
        com.shilapi.xcertplay.vehicle.adapters.pioneer.PalSteeringWheelInputAdapter.audioServiceForTest(context.applicationContext)
    }

    fun snapshot(): JSONObject = JSONObject(state.toString())

    fun start(
        context: Context,
        seconds: Int = DEFAULT_SECONDS,
        sampleRate: Int = SAMPLE_RATE,
        communicationMode: Boolean = true,
        sourceName: String = "MIC",
        gainDb: Int = 0,
        voiceEffects: Boolean = false,
        palMicType: Int = 0,
        report: (String) -> Unit = {},
    ): JSONObject {
        require(sampleRate in setOf(16_000, 32_000, 48_000))
        require(sourceName in setOf("MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION"))
        require(gainDb in 0..18)
        require(palMicType in setOf(0, 200, 230))
        synchronized(lock) {
            if (worker != null) return snapshot()
            val app = context.applicationContext
            val durationMs = seconds.coerceIn(1, 30) * 1000L
            val safeReport: (String) -> Unit = { runCatching { report(it) } }
            val part = CarMicTestStore.newPart(app)
            stopRequested = false
            state = JSONObject()
                .put("state", "starting")
                .put("targetMs", durationMs)
                .put("sampleRate", sampleRate)
                .put("channels", 1)
                .put("source", sourceName)
            val experimentLease = com.shilapi.xcertplay.pioneer.PioneerAudioExperiments.hold()
            val next = Thread({
                try { record(app, part, durationMs, sampleRate, communicationMode, sourceName, gainDb, voiceEffects, palMicType, safeReport) }
                finally { experimentLease.close() }
            }, "car-mic-test").apply {
                isDaemon = true
            }
            worker = next
            try { next.start() } catch (error: Throwable) { worker = null; experimentLease.close(); throw error }
        }
        return snapshot()
    }

    fun stop() {
        stopRequested = true
        worker?.takeUnless { it === Thread.currentThread() }?.let { it.join(2_500) }
    }

    private fun record(app: Context, part: File, targetMs: Long, sampleRate: Int, communicationMode: Boolean, sourceName: String, gainDb: Int, voiceEffects: Boolean, palMicType: Int, report: (String) -> Unit) {
        val manager = checkNotNull(app.getSystemService(AudioManager::class.java)) {
            "AudioManager unavailable"
        }
        val previousMode = manager.mode
        var captureStartedMs = SystemClock.elapsedRealtime()
        var recorder: AudioRecord? = null
        var output: RandomAccessFile? = null
        var lease: PioneerMicrophoneLease? = null
        var palLease: PioneerPalMicTestLease? = null
        var directController: Any? = null
        var directToken = false
        var gate = "unavailable"
        var modeChanged = false
        var routedType: Int? = null
        val startedAt = System.currentTimeMillis()
        var samples = 0L
        var nonzero = 0L
        var peak = 0
        var sumSquares = 0.0
        var emptyReads = 0
        var rawPeak = 0
        var rawSumSquares = 0.0
        var clippedSamples = 0L
        val gain = Math.pow(10.0, gainDb / 20.0)
        val effects = ArrayList<android.media.audiofx.AudioEffect>()
        val effectStatus = JSONObject()
        var preferredAccepted = false
        try {
            if (app.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                throw SecurityException("录音权限尚未授予")
            part.parentFile?.mkdirs()
            val out = RandomAccessFile(part, "rw").apply {
                setLength(0)
                write(ByteArray(WavPcm16.HEADER_BYTES))
                seek(WavPcm16.HEADER_BYTES.toLong())
            }
            output = out

            if (palMicType != 0) palLease = PioneerPalMicTestLease.acquire(app, palMicType, report, { !stopRequested }) { stopRequested = true }
            else lease = PioneerMicrophoneLease.acquire(app.packageName, report)
            if (palLease != null) {
                gate = "pal-$palMicType"
            } else if (lease != null) {
                gate = "wired-session"
            } else {
                check(PioneerUsbPolicy.matchesDevice()) {
                    "没有有线 CarPlay 会话，且当前不是已验证的先锋车机；请先连接车机后重试"
                }
                val type = Class.forName("jp.pioneer.ceam.MediaManager.MediaController")
                val controller = checkNotNull(type.getMethod("getInstance").invoke(null)) {
                    "车机 MediaController 不可用"
                }
                directController = controller
                val before = runCatching {
                    type.getMethod("getCurMicToken").invoke(controller)
                }.getOrNull()
                val granted = type.getMethod("requestMicOn", String::class.java)
                    .invoke(controller, app.packageName) == true
                report("MIC_TEST vendor gate before=$before granted=$granted")
                check(granted) { "车机拒绝麦克风请求；请先启动有线 CarPlay 会话后重试" }
                directToken = true
                gate = "standalone-vendor"
                Thread.sleep(150)
            }

            check(!stopRequested) { "录音已取消" }
            manager.mode = if (communicationMode) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
            modeChanged = true
            val minBuffer = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "AudioRecord 不可用（minBuffer=$minBuffer）" }
            val next = AudioRecord.Builder()
                .setAudioSource(when (sourceName) {
                    "VOICE_RECOGNITION" -> MediaRecorder.AudioSource.VOICE_RECOGNITION
                    "VOICE_COMMUNICATION" -> MediaRecorder.AudioSource.VOICE_COMMUNICATION
                    else -> MediaRecorder.AudioSource.MIC
                })
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build(),
                )
                .setBufferSizeInBytes(maxOf(minBuffer * 2, 5_120))
                .build()
            recorder = next
            check(next.state == AudioRecord.STATE_INITIALIZED) { "AudioRecord 初始化失败" }
            val builtIn = manager.getDevices(AudioManager.GET_DEVICES_INPUTS)
                .firstOrNull { it.type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }
            if (builtIn != null) preferredAccepted = next.setPreferredDevice(builtIn)
            if (voiceEffects) {
                fun effect(name: String, create: () -> android.media.audiofx.AudioEffect?) {
                    runCatching {
                        val fx = create()
                        if (fx != null) {
                            effects.add(fx)
                            val result = fx.setEnabled(true)
                            effectStatus.put(name, JSONObject().put("result", result).put("enabled", fx.enabled))
                        } else effectStatus.put(name, "unavailable")
                    }.onFailure { effectStatus.put(name, it.javaClass.simpleName) }
                }
                effect("aec") { android.media.audiofx.AcousticEchoCanceler.create(next.audioSessionId) }
                effect("ns") { android.media.audiofx.NoiseSuppressor.create(next.audioSessionId) }
                effect("agc") { android.media.audiofx.AutomaticGainControl.create(next.audioSessionId) }
            }
            next.startRecording()
            check(next.recordingState == AudioRecord.RECORDSTATE_RECORDING) { "AudioRecord 未开始录音" }
            routedType = runCatching { next.routedDevice?.type }.getOrNull()
            state = liveState(startedAt, targetMs, 0L, 0L, 0L, 0, 0.0, gate, routedType, sampleRate, sourceName)
            report("MIC_TEST recording gate=$gate source=$sourceName rate=$sampleRate mode=${manager.mode} gainDb=$gainDb preferred=$preferredAccepted effects=$effectStatus")

            val buffer = ShortArray(READ_SAMPLES)
            val bytes = ByteArray(READ_SAMPLES * 2)
            val started = SystemClock.elapsedRealtime()
            captureStartedMs = started
            val targetSamples = sampleRate.toLong() * targetMs / 1000L
            var lastUpdate = 0L
            while (!stopRequested && samples < targetSamples &&
                SystemClock.elapsedRealtime() - started < targetMs + 750L) {
                val n = next.read(buffer, 0, buffer.size, AudioRecord.READ_NON_BLOCKING)
                if (n < 0) error("AudioRecord 读取失败：$n")
                if (n == 0) {
                    emptyReads++
                    Thread.sleep(READ_EMPTY_SLEEP_MILLIS)
                    continue
                }
                var count = 0
                for (index in 0 until n) {
                    val raw = buffer[index].toInt()
                    rawPeak = maxOf(rawPeak, abs(raw))
                    rawSumSquares += raw.toDouble() * raw
                    val amplified = (raw * gain).roundToInt()
                    if (amplified !in -32768..32767) clippedSamples++
                    val value = amplified.coerceIn(-32768, 32767)
                    if (value != 0) nonzero++
                    val magnitude = abs(value)
                    if (magnitude > peak) peak = magnitude
                    sumSquares += value.toDouble() * value
                    bytes[count++] = (value and 0xFF).toByte()
                    bytes[count++] = ((value ushr 8) and 0xFF).toByte()
                }
                out.write(bytes, 0, count)
                samples += n
                val now = SystemClock.elapsedRealtime()
                if (now - lastUpdate >= LIVE_UPDATE_MILLIS) {
                    lastUpdate = now
                    state = liveState(
                        startedAt, targetMs, now - started, samples, nonzero, peak,
                        sumSquares, gate, routedType, sampleRate, sourceName,
                    )
                }
            }
            runCatching { next.stop() }
            routedType = runCatching { next.routedDevice?.type }.getOrNull() ?: routedType

            val dataBytes = samples * 2
            WavPcm16.writeHeader(out, sampleRate, 1, dataBytes)
            out.close()
            output = null
            val finalFile = CarMicTestStore.completedFile(part)
            val renamed = part.renameTo(finalFile) ||
                runCatching {
                    part.copyTo(finalFile, overwrite = true)
                    part.delete()
                    true
                }.getOrDefault(false)
            check(renamed) { "无法保存录音文件" }

            val durationMs = samples * 1000L / sampleRate
            val rms = rmsDbfs(sumSquares, samples)
            val verdict = verdict(samples, nonzero, peak, rms)
            val metadataSaved = CarMicTestStore.saveMetadata(
                context = app,
                audio = finalFile,
                createdAt = startedAt,
                durationMs = durationMs,
                sampleRate = sampleRate,
                channels = 1,
                peak = peak,
                rmsDbfs = rms,
                verdict = verdict.label,
                verdictKey = verdict.key,
                source = sourceName,
                gate = gate,
                routedDeviceType = routedType,
                diagnostics = JSONObject().put("gainDb", gainDb)
                    .put("rawRmsDbfs", rmsDbfs(rawSumSquares, samples)?.round1() ?: JSONObject.NULL)
                    .put("rawPeakDbfs", peakDbfs(rawPeak)?.round1() ?: JSONObject.NULL)
                    .put("clippedSamples", clippedSamples).put("effects", effectStatus)
                    .put("effectPolicy", if (voiceEffects) "REQUEST_ENABLE" else "SOURCE_DEFAULT_UNVERIFIED")
                    .put("audioMode", if (communicationMode) "COMMUNICATION" else "NORMAL"),
            )
            state = JSONObject()
                .put("state", "complete")
                .put("startedAt", startedAt)
                .put("targetMs", targetMs)
                .put("durationMs", durationMs)
                .put("elapsedMs", durationMs)
                .put("samples", samples)
                .put("nonzeroSamples", nonzero)
                .put("emptyReads", emptyReads)
                .put("peak", peak)
                .put("gainDb", gainDb)
                .put("rawPeakDbfs", peakDbfs(rawPeak)?.round1() ?: JSONObject.NULL)
                .put("rawRmsDbfs", rmsDbfs(rawSumSquares, samples)?.round1() ?: JSONObject.NULL)
                .put("clippedSamples", clippedSamples)
                .put("clippedRatio", if (samples > 0) clippedSamples.toDouble() / samples else 0.0)
                .put("preferredBuiltInAccepted", preferredAccepted)
                .put("effects", effectStatus)
                .put("effectPolicy", if (voiceEffects) "REQUEST_ENABLE" else "SOURCE_DEFAULT_UNVERIFIED")
                .put("audioMode", if (communicationMode) "COMMUNICATION" else "NORMAL")
                .put("wallDurationMs", SystemClock.elapsedRealtime() - captureStartedMs)
                .put("actualSampleRate", next.sampleRate)
                .put("zeroRatio", if (samples > 0) (samples - nonzero).toDouble() / samples else 1.0)
                .put("sampleRate", sampleRate)
                .put("channels", 1)
                .put("source", sourceName)
                .put("gate", gate)
                .put("fileName", finalFile.name)
                .put("verdict", verdict.label)
                .put("verdictKey", verdict.key)
                .put("stoppedEarly", stopRequested)
                .put("metadataSaved", metadataSaved)
                .put("routedDeviceType", routedType ?: JSONObject.NULL)
                .put("peakDbfs", peakDbfs(peak)?.round1() ?: JSONObject.NULL)
                .put("rmsDbfs", rms?.round1() ?: JSONObject.NULL)
            report(
                "MIC_TEST complete file=${finalFile.name} durationMs=$durationMs samples=$samples " +
                    "nonzero=$nonzero peak=$peak rmsDbfs=${rms?.let { "%.1f".format(Locale.US, it) } ?: "silent"} " +
                    "verdict=${verdict.key} gate=$gate route=$routedType emptyReads=$emptyReads",
            )
        } catch (error: Exception) {
            var cause: Throwable = error
            while (cause is java.lang.reflect.InvocationTargetException && cause.targetException != null) cause = cause.targetException
            val message = cause.message?.takeIf { it.isNotBlank() } ?: cause.javaClass.simpleName
            state = JSONObject()
                .put("state", "failed")
                .put("targetMs", targetMs)
                .put("error", message)
            report("MIC_TEST failed ${cause.javaClass.simpleName}: $message")
            runCatching { output?.close() }
            output = null
            runCatching { part.delete() }
        } finally {
            runCatching { output?.close() }
            runCatching { recorder?.stop() }
            effects.forEach { runCatching { it.release() } }
            runCatching { recorder?.release() }
            runCatching { lease?.close() }
            runCatching { palLease?.close() }
            if (directToken) runCatching {
                val controller = directController ?: return@runCatching
                val type = controller.javaClass
                if (type.getMethod("getCurMicToken").invoke(controller) == app.packageName) {
                    val released = type.getMethod("requestMicOff", String::class.java)
                        .invoke(controller, app.packageName)
                    report("MIC_TEST vendor gate released=$released")
                }
            }
            if (modeChanged && manager.mode == (if (communicationMode) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL)) {
                runCatching { manager.mode = previousMode }
            }
            synchronized(lock) {
                if (worker === Thread.currentThread()) worker = null
            }
        }
    }

    private fun liveState(
        startedAt: Long,
        targetMs: Long,
        elapsedMs: Long,
        samples: Long,
        nonzero: Long,
        peak: Int,
        sumSquares: Double,
        gate: String,
        routedType: Int?,
        sampleRate: Int,
        sourceName: String,
    ): JSONObject = JSONObject()
        .put("state", "recording")
        .put("startedAt", startedAt)
        .put("targetMs", targetMs)
        .put("elapsedMs", elapsedMs)
        .put("samples", samples)
        .put("nonzeroSamples", nonzero)
        .put("peak", peak)
        .put("sampleRate", sampleRate)
        .put("channels", 1)
        .put("source", sourceName)
        .put("gate", gate)
        .put("routedDeviceType", routedType ?: JSONObject.NULL)
        .put("peakDbfs", peakDbfs(peak)?.round1() ?: JSONObject.NULL)
        .put("rmsDbfs", rmsDbfs(sumSquares, samples)?.round1() ?: JSONObject.NULL)

    private data class Verdict(val label: String, val key: String)

    private fun verdict(samples: Long, nonzero: Long, peak: Int, rms: Double?): Verdict {
        if (samples <= 0L) return Verdict("无声（没有采样）", "silent")
        if (nonzero * 20 < samples) return Verdict("几乎无声（超过 95% 采样为零）", "silent")
        if (rms == null || rms < -50.0) return Verdict("几乎无声", "silent")
        if (rms < -40.0) return Verdict("音量偏低", "low")
        if (peak >= 32_700 || rms > -12.0) return Verdict("过响或削波", "hot")
        return Verdict("可用", "good")
    }

    private fun peakDbfs(peak: Int): Double? =
        if (peak <= 0) null else 20 * log10(peak / 32768.0)

    private fun rmsDbfs(sumSquares: Double, samples: Long): Double? =
        if (samples <= 0L || sumSquares <= 0.0) null
        else 20 * log10(sqrt(sumSquares / samples) / 32768.0)

    private fun Double.round1(): Double = (this * 10).roundToInt() / 10.0
}

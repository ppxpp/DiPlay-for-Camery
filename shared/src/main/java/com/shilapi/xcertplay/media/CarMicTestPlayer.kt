package com.shilapi.xcertplay.media

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.shilapi.xcertplay.compat.AudioFocusRequestCompat
import com.shilapi.xcertplay.vehicle.runtime.VehicleAudioRouting
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.min

/**
 * Replays a local test recording through the same media policy as CarPlay media: the Pioneer
 * media source is selected and the legacy 401 media stream/audio focus are used when available.
 */
object CarMicTestPlayer {
    private val gate = Any()
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var worker: Thread? = null
    @Volatile private var currentRun: PlaybackRun? = null
    @Volatile private var state: JSONObject = JSONObject().put("state", "idle")

    fun snapshot(): JSONObject = JSONObject(state.toString())

    fun play(context: Context, file: File, standardRoute: Boolean = false, stereo: Boolean = false, playbackStream: Int = 0, report: (String) -> Unit = {}): JSONObject {
        require(playbackStream in setOf(0, 401, 403))
        require(!standardRoute || playbackStream == 0)
        require(playbackStream == 0 || com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice())
        require(file.isFile) { "录音文件不存在" }
        val app = context.applicationContext
        val safeReport: (String) -> Unit = { runCatching { report(it) } }
        synchronized(gate) {
            currentRun?.let {
                it.stopReason = "被新的回放替换"
                it.stopped = true
            }
            worker?.takeUnless { it === Thread.currentThread() }?.join(1_200)
            val run = PlaybackRun()
            currentRun = run
            state = JSONObject()
                .put("state", "starting")
                .put("fileName", file.name)
            val next = Thread({ playback(app, file, run, standardRoute, stereo, playbackStream, safeReport) }, "car-mic-playback").apply {
                isDaemon = true
            }
            worker = next
            next.start()
        }
        return snapshot()
    }

    fun stop() {
        synchronized(gate) {
            val run = currentRun ?: return
            run.stopReason = "用户停止"
            run.stopped = true
            worker?.takeUnless { it === Thread.currentThread() }?.let { it.join(1_500) }
        }
    }

    private class PlaybackRun {
        @Volatile var stopped = false
        @Volatile var stopReason: String? = null
    }

    private fun playback(
        app: Context,
        file: File,
        run: PlaybackRun,
        standardRoute: Boolean,
        stereo: Boolean,
        playbackStream: Int,
        report: (String) -> Unit,
    ) {
        val manager = checkNotNull(app.getSystemService(AudioManager::class.java)) {
            "AudioManager unavailable"
        }
        var track: AudioTrack? = null
        var focus: AudioFocusRequestCompat? = null
        try {
            val info = WavPcm16.read(file) ?: error("录音文件不是有效的 PCM16 WAV")
            check(info.dataBytes > 0L) { "录音没有采样数据" }
            val routing = VehicleAudioRouting.selected
            val sourceReport = rateLimited(report)
            if (routing.available) runCatching { routing.selectMediaSource(sourceReport) }

            val stream = if (standardRoute) null else if (playbackStream != 0) playbackStream else runCatching { routing.streamType(AudioChannel.MEDIA) }.getOrNull()
            val legacyStream = stream?.takeIf { type ->
                (playbackStream != 0 && type == playbackStream) || runCatching { routing.acceptsLegacyStream(type) }.getOrDefault(false)
            }
            val legacyAttributes = legacyStream?.let { type ->
                runCatching { AudioAttributes.Builder().setLegacyStreamType(type).build() }.getOrNull()
            }
            val outputChannels = if (stereo && info.channels == 1) 2 else info.channels
            val channelMask = if (outputChannels >= 2) AudioFormat.CHANNEL_OUT_STEREO
            else AudioFormat.CHANNEL_OUT_MONO
            val minBuffer = AudioTrack.getMinBufferSize(
                info.sampleRate,
                channelMask,
                AudioFormat.ENCODING_PCM_16BIT,
            )
            check(minBuffer > 0) { "AudioTrack 缓冲区不可用（minBuffer=$minBuffer）" }
            val bufferBytes = maxOf(minBuffer * 2, 8_192)
            var routeLabel = "usage"
            var focusAttributes = mediaAttributes()
            val built: AudioTrack
            if (legacyStream != null) {
                @Suppress("DEPRECATION")
                val legacyTrack = runCatching {
                    AudioTrack(
                        legacyStream,
                        info.sampleRate,
                        channelMask,
                        AudioFormat.ENCODING_PCM_16BIT,
                        bufferBytes,
                        AudioTrack.MODE_STREAM,
                    )
                }.getOrNull()
                if (legacyTrack != null && legacyTrack.state == AudioTrack.STATE_INITIALIZED) {
                    routeLabel = "streamType=$legacyStream"
                    focusAttributes = legacyAttributes ?: focusAttributes
                    built = legacyTrack
                } else {
                    legacyTrack?.release()
                    check(playbackStream == 0) { "指定回放通道 $playbackStream 不可用，已停止对照" }
                    routeLabel = "usage(fallback)"
                    built = buildUsageTrack(focusAttributes, info.sampleRate, channelMask, bufferBytes)
                }
            } else {
                built = buildUsageTrack(focusAttributes, info.sampleRate, channelMask, bufferBytes)
            }
            track = built
            built.setVolume(1f)
            val volumeStream = legacyStream ?: AudioManager.STREAM_MUSIC
            report("MIC_TEST playback volume stream=$volumeStream index=${runCatching { manager.getStreamVolume(volumeStream) }.getOrNull()} max=${runCatching { manager.getStreamMaxVolume(volumeStream) }.getOrNull()} mode=${manager.mode} contentType=${runCatching { (AudioTrack::class.java.getMethod("getAudioAttributes").invoke(built) as? AudioAttributes)?.contentType }.getOrNull()}")
            report("MIC_TEST playback config standardRoute=$standardRoute inputChannels=${info.channels} outputChannels=$outputChannels rate=${info.sampleRate} trackRate=${built.sampleRate}")

            val focusStream = if (playbackStream != 0 && !standardRoute && legacyStream != null) legacyStream else runCatching { routing.focusStream(focusAttributes) }.getOrNull()
            val focusResult = runCatching {
                focus = AudioFocusRequestCompat(
                    AudioManager.AUDIOFOCUS_GAIN,
                    focusAttributes,
                    AudioManager.OnAudioFocusChangeListener { change ->
                        if (change == AudioManager.AUDIOFOCUS_LOSS ||
                            change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                            run.stopReason = "音频焦点被占用（$change）"
                            run.stopped = true
                            runCatching { report("MIC_TEST playback focus change=$change; stopping") }
                        }
                    },
                    main,
                    focusStream,
                )
                focus!!.request(manager)
            }.getOrDefault(-1)
            if (routing.available) runCatching { routing.selectMediaSource(sourceReport) }

            publish(run, playbackState(
                status = "playing",
                file = file,
                info = info,
                elapsedMs = 0L,
                routedType = null,
                route = routeLabel,
                focusGranted = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            ))
            report(
                "MIC_TEST playback start file=${file.name} rate=${info.sampleRate} " +
                    "channels=${info.channels} route=$routeLabel focus=$focusResult",
            )
            val startedAt = SystemClock.elapsedRealtime()
            built.play()

            RandomAccessFile(file, "r").use { input ->
                input.seek(info.dataOffset)
                var remaining = info.dataBytes
                val chunk = ByteArray(4_096)
                val stereoChunk = ByteArray(chunk.size * 2)
                var frames = 0L
                var routedType: Int? = null
                var lastSourceCheck = startedAt
                var lastStateUpdate = 0L
                while (!run.stopped && remaining > 0L) {
                    val want = min(remaining, chunk.size.toLong()).toInt()
                    val read = input.read(chunk, 0, want)
                    if (read <= 0) break
                    val outputBytes = if (outputChannels == 2 && info.channels == 1) {
                        for (i in 0 until read / 2) {
                            stereoChunk[i * 4] = chunk[i * 2]
                            stereoChunk[i * 4 + 1] = chunk[i * 2 + 1]
                            stereoChunk[i * 4 + 2] = chunk[i * 2]
                            stereoChunk[i * 4 + 3] = chunk[i * 2 + 1]
                        }
                        read * 2
                    } else read
                    val output = if (outputBytes == read) chunk else stereoChunk
                    var offset = 0
                    while (offset < outputBytes && !run.stopped) {
                        val written = built.write(output, offset, outputBytes - offset, AudioTrack.WRITE_BLOCKING)
                        if (written < 0) error("AudioTrack 写入失败：$written")
                        if (written == 0) {
                            Thread.sleep(5)
                            continue
                        }
                        offset += written
                    }
                    remaining -= read
                    frames += read.toLong() / (2L * info.channels)
                    val now = SystemClock.elapsedRealtime()
                    if (routedType == null && now - startedAt >= 300L) {
                        routedType = runCatching { built.routedDevice?.type }.getOrNull()
                    }
                    if (now - lastSourceCheck >= 1_000L) {
                        if (routing.available) runCatching { routing.selectMediaSource(sourceReport) }
                        lastSourceCheck = now
                    }
                    if (now - lastStateUpdate >= 200L) {
                        lastStateUpdate = now
                        publish(run, playbackState(
                            status = "playing",
                            file = file,
                            info = info,
                            elapsedMs = frames * 1000L / info.sampleRate,
                            routedType = routedType,
                            route = routeLabel,
                            focusGranted = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                        ))
                    }
                }
                val totalFrames = frames
                val drainDeadline = SystemClock.elapsedRealtime() + 2_500L
                while (!run.stopped && built.playbackHeadPosition.toLong() < totalFrames &&
                    SystemClock.elapsedRealtime() < drainDeadline) {
                    Thread.sleep(20)
                }
                val elapsedMs = totalFrames * 1000L / info.sampleRate
                publish(run, playbackState(
                    status = if (run.stopped) "stopped" else "complete",
                    file = file,
                    info = info,
                    elapsedMs = elapsedMs,
                    routedType = routedType,
                    route = routeLabel,
                    focusGranted = focusResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
                    reason = run.stopReason,
                ))
                report(
                    "MIC_TEST playback ${if (run.stopped) "stopped" else "complete"} " +
                        "file=${file.name} elapsedMs=$elapsedMs route=$routeLabel " +
                        "routedDeviceType=${routedType ?: "unknown"}",
                )
            }
        } catch (error: Exception) {
            val message = error.message?.takeIf { it.isNotBlank() } ?: error.javaClass.simpleName
            publish(run, JSONObject()
                .put("state", "failed")
                .put("fileName", file.name)
                .put("error", message))
            report("MIC_TEST playback failed ${error.javaClass.simpleName}: $message")
        } finally {
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.stop() }
            runCatching { track?.release() }
            focus?.let { request -> runCatching { request.abandon(manager) } }
            if (worker === Thread.currentThread()) {
                worker = null
                if (currentRun === run) currentRun = null
            }
        }
    }

    private fun mediaAttributes(): AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
        .build()

    private fun publish(run: PlaybackRun, value: JSONObject) {
        if (currentRun === run) state = value
    }

    private fun buildUsageTrack(
        attributes: AudioAttributes,
        sampleRate: Int,
        channelMask: Int,
        bufferBytes: Int,
    ): AudioTrack {
        val built = AudioTrack.Builder()
            .setAudioAttributes(attributes)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setChannelMask(channelMask)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(bufferBytes)
            .build()
        check(built.state == AudioTrack.STATE_INITIALIZED) { "AudioTrack 初始化失败" }
        return built
    }

    private fun playbackState(
        status: String,
        file: File,
        info: WavPcm16.Info,
        elapsedMs: Long,
        routedType: Int?,
        route: String,
        focusGranted: Boolean,
        reason: String? = null,
    ): JSONObject {
        val json = JSONObject()
            .put("state", status)
            .put("fileName", file.name)
            .put("durationMs", info.dataBytes * 1000L / (info.sampleRate.toLong() * info.channels * 2))
            .put("elapsedMs", elapsedMs)
            .put("sampleRate", info.sampleRate)
            .put("channels", info.channels)
            .put("route", route)
            .put("focusGranted", focusGranted)
            .put("routedDeviceType", routedType ?: JSONObject.NULL)
        if (reason != null) json.put("reason", reason)
        return json
    }

    private fun rateLimited(report: (String) -> Unit): (String) -> Unit {
        var last = ""
        return { line ->
            if (line != last) {
                last = line
                runCatching { report(line) }
            }
        }
    }
}

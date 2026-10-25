package com.shilapi.xcertplay

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.media.CarMicTestPlayer
import com.shilapi.xcertplay.media.CarMicTestRecorder
import com.shilapi.xcertplay.media.CarMicTestRecording
import com.shilapi.xcertplay.media.CarMicTestStore
import com.shilapi.xcertplay.pioneer.PioneerDebugRuntime
import org.json.JSONObject
import java.util.Locale
import kotlin.math.log10

/**
 * Manual validation page for the CarPlay uplink microphone path. Recordings stay in private
 * app storage and replay through the vehicle's media route, not a generic phone speaker.
 */
class CarMicTestActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var recordStatus: TextView
    private lateinit var playbackStatus: TextView
    private lateinit var recordButton: Button
    private lateinit var stopRecordButton: Button
    private lateinit var stopPlaybackButton: Button
    private lateinit var history: LinearLayout
    private var guidedPhase = "idle"
    private var guidedRunId = ""
    private var guidedFile = ""
    private var guidedFeedback = ""
    private var guidedError: String? = null
    private var guidedDialog: AlertDialog? = null
    private var guidedRecording = JSONObject()
    private var guidedPlayback = JSONObject()
    private var phaseStartedMs = 0L
    private var permissionForGuide = false
    private var resumed = false
    private var lastRecordState = ""
    private var historySignature = ""
    private val refresh = object : Runnable {
        override fun run() {
            refreshUi()
            advanceGuidedTest()
            handler.postDelayed(this, 500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildContent())
        refreshHistory(force = true)
        refreshUi()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (savedInstanceState == null) handler.post { prepareGuidedTest() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (guidedPhase in setOf("idle", "complete", "cancelled", "failed")) {
            setIntent(intent)
            prepareGuidedTest()
        }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.removeCallbacks(refresh)
        handler.post(refresh)
        refreshHistory(force = true)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(refresh)
        super.onPause()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        guidedDialog?.dismiss()
        if (guidedPhase !in setOf("idle", "complete", "cancelled", "failed")) cancelGuidedTest()
        runCatching { CarMicTestPlayer.stop() }
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode != REQUEST_RECORD_AUDIO) return
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            if (permissionForGuide) {
                permissionForGuide = false
                beginGuidedRecording()
            } else startRecording()
        } else {
            permissionForGuide = false
            if (guidedPhase == "permission") failGuidedTest("没有录音权限")
            toast("没有录音权限，无法使用车机麦克风")
        }
    }

    private fun buildContent(): View {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(24))
        }
        column.addView(Button(this).apply {
            text = "返回"
            setOnClickListener { finish() }
        })
        column.addView(TextView(this).apply {
            text = "车机麦克风录音测试"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
        })
        column.addView(TextView(this).apply {
            text = "用 CarPlay 上行同款链路录制 5 秒，并通过车机媒体通道回放。\n" +
                "1. 不需要连接 CarPlay，会自动申请车机麦克风令牌。\n" +
                "2. 在弹窗中点击开始，正常音量连续说话 5 秒。\n" +
                "3. 录完自动回放；听完在弹窗选择反馈，也可以重播。\n" +
                "测试时不要同时进行通话或 Siri，避免两条链路争用车机麦克风。\n" +
                "录音只保存在本机应用私有目录，最多保留 ${CarMicTestStore.MAX_RECORDINGS} 条，可一键清空。"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(12))
            setTextColor(0xFFB8C2CC.toInt())
        })
        recordStatus = TextView(this).apply {
            text = "就绪"
            textSize = 16f
            isFocusable = false
            setPadding(0, dp(8), 0, dp(8))
        }
        column.addView(recordStatus)

        val recordRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        recordButton = Button(this).apply {
            text = "引导录音与回放（5 秒）"
            setOnClickListener { prepareGuidedTest() }
        }
        stopRecordButton = Button(this).apply {
            text = "停止录制"
            isEnabled = false
            setOnClickListener {
                if (guidedPhase == "recording") cancelGuidedTest() else CarMicTestRecorder.stop()
                toast("录音已停止，正在保存")
            }
        }
        recordRow.addView(recordButton, rowParams())
        recordRow.addView(stopRecordButton, rowParams())
        column.addView(recordRow)

        playbackStatus = TextView(this).apply {
            text = "回放：未播放。回放会切到车机媒体通道。"
            textSize = 14f
            setPadding(0, dp(12), 0, dp(4))
            isFocusable = false
        }
        column.addView(playbackStatus)
        stopPlaybackButton = Button(this).apply {
            text = "停止回放"
            isEnabled = false
            setOnClickListener {
                if (guidedPhase == "playback") cancelGuidedTest() else CarMicTestPlayer.stop()
                toast("已停止回放")
            }
        }
        column.addView(
            stopPlaybackButton,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(4) },
        )
        column.addView(TextView(this).apply {
            text = "回放使用 CarPlay 媒体同款车机适配：优先 Pioneer 媒体流 401 / TAI_AUDIO 源，并申请媒体音频焦点。"
            textSize = 12f
            setTextColor(0xFF9AA4AE.toInt())
            setPadding(0, dp(6), 0, 0)
        })

        val historyHeader = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(20), 0, dp(6))
        }
        historyHeader.addView(
            TextView(this).apply {
                text = "历史录音"
                textSize = 18f
                setTypeface(typeface, Typeface.BOLD)
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        historyHeader.addView(Button(this).apply {
            text = "一键清空"
            setOnClickListener { confirmClearAll() }
        })
        column.addView(historyHeader)

        history = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        column.addView(history)
        return ScrollView(this).apply { addView(column) }
    }

    private fun startRecording() {
        if (CarMicTestRecorder.isRunning()) {
            toast("录音正在进行")
            return
        }
        if (isPlaybackRunning()) CarMicTestPlayer.stop()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
            return
        }
        val result = CarMicTestRecorder.start(this, CarMicTestRecorder.DEFAULT_SECONDS,
            sampleRate = intent.getIntExtra("captureRate", CarMicTestRecorder.SAMPLE_RATE),
            communicationMode = intent.getStringExtra("captureMode") == "COMMUNICATION",
            sourceName = intent.getStringExtra("captureSource") ?: "MIC",
            gainDb = intent.getIntExtra("gainDb", 0),
            voiceEffects = intent.getBooleanExtra("voiceEffects", false),
            palMicType = intent.getIntExtra("palMicType", 0)) { line ->
            PioneerDebugRuntime.log(applicationContext, line)
        }
        if (result.optString("state") == "failed") {
            toast(result.optString("error", "无法开始录音"))
        }
    }

    private fun defaultPlaybackStream(): Int =
        if (com.shilapi.xcertplay.pioneer.PioneerUsbPolicy.matchesDevice() &&
            !intent.getBooleanExtra("standardPlayback", false)) 403 else 0

    private fun play(item: CarMicTestRecording) {
        if (CarMicTestRecorder.isRunning()) {
            toast("请先停止录音再回放")
            return
        }
        CarMicTestPlayer.play(this, item.file,
            standardRoute = intent.getBooleanExtra("standardPlayback", false),
            stereo = intent.getBooleanExtra("stereoPlayback", true),
            playbackStream = intent.getIntExtra("playbackStream", defaultPlaybackStream())) { line ->
            PioneerDebugRuntime.log(applicationContext, line)
        }
        toast("正在通过车机媒体通道回放")
    }

    private fun confirmClearAll() {
        if (CarMicTestRecorder.isRunning()) {
            toast("请先停止录音再清空")
            return
        }
        AlertDialog.Builder(this)
            .setTitle("清空全部录音？")
            .setMessage("将删除本机保存的全部麦克风测试录音，无法恢复。")
            .setNegativeButton("取消", null)
            .setPositiveButton("清空") { _, _ ->
                CarMicTestPlayer.stop()
                val count = runCatching { CarMicTestStore.clearAll(this) }.getOrElse {
                    toast("清空失败：${it.message}")
                    0
                }
                refreshHistory(force = true)
                toast("已清空 $count 条录音")
            }
            .show()
    }

    private fun refreshUi() {
        val recording = CarMicTestRecorder.snapshot()
        val recordState = recording.optString("state", "idle")
        val recordRunning = CarMicTestRecorder.isRunning() ||
            recordState == "starting" || recordState == "recording"
        val playback = CarMicTestPlayer.snapshot()
        val playbackState = playback.optString("state", "idle")
        val playbackRunning = playbackState == "starting" || playbackState == "playing"

        recordButton.isEnabled = !recordRunning && !playbackRunning &&
            guidedPhase in setOf("idle", "complete", "cancelled", "failed")
        stopRecordButton.isEnabled = recordRunning
        stopPlaybackButton.isEnabled = playbackRunning
        recordStatus.text = when (recordState) {
            "starting" -> "正在申请车机麦克风…"
            "recording" -> buildString {
                append("录制中 ")
                append(seconds(recording.optLong("elapsedMs")))
                append(" / ")
                append(seconds(recording.optLong("targetMs")))
                append(" 秒")
                append("\n峰值 ").append(jsonDbfs(recording, "peakDbfs"))
                append(" · RMS ").append(jsonDbfs(recording, "rmsDbfs"))
                append(" · 入口 ").append(gateLabel(recording.optString("gate")))
                val route = recording.optInt("routedDeviceType", -1)
                if (route >= 0) append(" · 路由 type=").append(route)
            }
            "complete" -> buildString {
                append("录制完成：").append(recording.optString("verdict"))
                append("\n")
                append(seconds(recording.optLong("durationMs"))).append(" 秒 · 峰值 ")
                append(jsonDbfs(recording, "peakDbfs"))
                append(" · RMS ").append(jsonDbfs(recording, "rmsDbfs"))
                append("\n入口 ").append(gateLabel(recording.optString("gate")))
                append(" · 文件 ").append(recording.optString("fileName"))
                if (recording.optBoolean("stoppedEarly")) append(" · 提前停止")
                if (!recording.optBoolean("metadataSaved", true)) append(" · 指标未保存")
            }
            "failed" -> "录制失败：" + recording.optString("error", "未知错误")
            else -> buildString {
                append("就绪。")
                append(
                    if (com.shilapi.xcertplay.pioneer.PioneerWiredSession.adaptationActive) {
                        "有线会话已激活"
                    } else {
                        "有线会话未激活（将尝试直接申请车机麦克风令牌）"
                    },
                )
                append("。点击开始录制。")
            }
        }
        if (recordState == "complete" && lastRecordState != "complete") {
            refreshHistory(force = true)
        }
        lastRecordState = recordState

        playbackStatus.text = when (playbackState) {
            "starting" -> "回放准备中…"
            "playing" -> buildString {
                append("回放中：").append(playback.optString("fileName"))
                append(" ").append(seconds(playback.optLong("elapsedMs")))
                append(" / ").append(seconds(playback.optLong("durationMs"))).append(" 秒")
                append("\n通道 ").append(playback.optString("route"))
                append(" · 焦点 ").append(if (playback.optBoolean("focusGranted")) "已获得" else "未获得")
                val route = playback.optInt("routedDeviceType", -1)
                if (route >= 0) append(" · 输出 type=").append(route)
            }
            "complete" -> "回放完成：" + playback.optString("fileName")
            "stopped" -> {
                val reason = playback.optString("reason").takeIf { it.isNotBlank() }
                "回放已停止" + (reason?.let { "（$it）" } ?: "")
            }
            "failed" -> "回放失败：" + playback.optString("error", "未知错误")
            else -> "回放：未播放。回放会切到车机媒体通道。"
        }
    }

    private fun prepareGuidedTest() {
        if (isFinishing || isDestroyed || guidedDialog?.isShowing == true) return
        if (CarMicTestRecorder.isRunning() || isPlaybackRunning()) {
            toast("请先停止当前录音或回放")
            return
        }
        guidedRunId = java.util.UUID.randomUUID().toString()
        guidedFile = ""
        guidedFeedback = ""
        guidedRecording = JSONObject()
        guidedPlayback = JSONObject()
        val replay = intent.getStringExtra("replayFile").orEmpty()
        if (replay.isNotEmpty()) {
            val item = CarMicTestStore.recordings(this).firstOrNull { it.file.name == replay }
            if (item == null) { failGuidedTest("未找到指定录音"); return }
            guidedFile = replay
            guidedRecording = JSONObject().put("fileName", replay).put("sampleRate", item.sampleRate)
                .put("peak", item.peak).put("rmsDbfs", item.rmsDbfs ?: JSONObject.NULL)
                .put("replayOnly", true)
        }
        setGuidedPhase("waiting-confirmation")
        guidedDialog = AlertDialog.Builder(this)
            .setTitle(if (replay.isEmpty()) "准备录音 · 5 秒" else "同一段录音 · 回放对照")
            .setMessage(if (replay.isNotEmpty()) "本轮不需要说话。点击开始后，听同一段录音并选择反馈。\n\n保持车机媒体音量不变。" else "无需连接 CarPlay。请在车辆停稳后，保持媒体音量不变。\n\n点击开始后，请正常音量连续说：\n“这是车机麦克风测试，一二三四五。”\n\n录完会自动回放，听完直接在车机选择反馈，不用查看聊天。")
            .setCancelable(false)
            .setNegativeButton("取消") { _, _ -> cancelGuidedTest() }
            .setPositiveButton("开始，我准备好了") { _, _ ->
                guidedDialog = null
                if (replay.isNotEmpty()) replayGuidedTest()
                else if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                    permissionForGuide = true
                    setGuidedPhase("permission")
                    requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_RECORD_AUDIO)
                } else beginGuidedRecording()
            }.show()
        if (intent.getIntExtra("palMicType", 0) != 0 && replay.isEmpty()) {
            val dialog = guidedDialog!!
            setGuidedPhase("preparing")
            dialog.setTitle("正在准备车机麦克风，请稍候")
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
            Thread({
                val prepared = runCatching { CarMicTestRecorder.preparePal(applicationContext) }
                handler.post {
                    if (guidedPhase == "preparing" && guidedDialog === dialog && dialog.isShowing && !isFinishing) {
                        prepared.onSuccess {
                            setGuidedPhase("waiting-confirmation")
                            dialog.setTitle("麦克风已就绪 · 准备录音 5 秒")
                            dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        }.onFailure { failGuidedTest(it.message ?: "车机麦克风服务不可用") }
                    }
                }
            }, "mic-test-pal-prepare").apply { isDaemon = true; start() }
        }
    }

    private fun beginGuidedRecording() {
        setGuidedPhase("recording")
        startRecording()
        guidedDialog = AlertDialog.Builder(this)
            .setTitle("请开始说话")
            .setMessage("正在准备麦克风，随后录制 5 秒。请正常音量连续说话。")
            .setCancelable(false)
            .setNegativeButton("停止本轮") { _, _ -> cancelGuidedTest() }
            .show()
    }

    private fun advanceGuidedTest() {
        if (!resumed) return
        when (guidedPhase) {
            "recording" -> {
                val rec = CarMicTestRecorder.snapshot()
                guidedRecording = rec
                guidedDialog?.setMessage(if (rec.optString("state") == "recording")
                    "请继续说话：${seconds(rec.optLong("elapsedMs"))} / 5 秒\n\n这是车机麦克风测试，一二三四五。"
                else "正在准备麦克风或保存录音，请稍候…")
                if (!CarMicTestRecorder.isRunning()) {
                    when (rec.optString("state")) {
                        "complete" -> {
                            if (rec.optBoolean("stoppedEarly")) failGuidedTest("录音提前停止，请重新录制")
                            else {
                                guidedFile = rec.optString("fileName")
                                replayGuidedTest()
                            }
                        }
                        "failed" -> failGuidedTest(rec.optString("error", "录音失败"))
                    }
                }
            }
            "playback" -> {
                val play = CarMicTestPlayer.snapshot()
                guidedPlayback = play
                guidedDialog?.setMessage("请听刚才的录音，播完会弹出反馈选项。\n\n${seconds(play.optLong("elapsedMs"))} / ${seconds(play.optLong("durationMs"))} 秒")
                when (play.optString("state")) {
                    "complete" -> showGuidedFeedback()
                    "failed", "stopped" -> failGuidedTest(play.optString("error", play.optString("reason", "回放已停止")))
                }
            }
        }
        if (guidedPhase in setOf("recording", "playback") &&
            android.os.SystemClock.elapsedRealtime() - phaseStartedMs > 45_000L) failGuidedTest("本轮超时，请重试")
        publishGuidedState()
    }

    private fun replayGuidedTest() {
        val item = CarMicTestStore.recordings(this).firstOrNull { it.file.name == guidedFile }
        if (item == null) {
            failGuidedTest("未找到本轮录音")
            return
        }
        guidedDialog?.dismiss()
        setGuidedPhase("playback")
        runCatching { play(item) }.onFailure { failGuidedTest(it.message ?: "回放失败"); return }
        guidedDialog = AlertDialog.Builder(this)
            .setTitle("正在自动回放")
            .setMessage("请听录音，播完选择反馈。")
            .setCancelable(false)
            .setNegativeButton("停止本轮") { _, _ -> cancelGuidedTest() }
            .show()
    }

    private fun showGuidedFeedback() {
        guidedDialog?.dismiss()
        setGuidedPhase("feedback")
        val labels = arrayOf("正常", "偏小", "无声", "回声", "音调/语速异常", "破音", "杂音", "断续")
        val keys = arrayOf("normal", "quiet", "silent", "echo", "pitch-or-speed", "distortion", "noise", "stutter")
        val selected = BooleanArray(labels.size)
        val checks = ArrayList<android.widget.CheckBox>()
        val choices = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
        }
        for (rowIndex in 0 until labels.size / 2) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (column in 0..1) {
                val index = rowIndex * 2 + column
                val check = android.widget.CheckBox(this).apply {
                    text = labels[index]
                    textSize = 15f
                    minHeight = dp(40)
                    setOnCheckedChangeListener { _, checked ->
                        selected[index] = checked
                        if (checked && index == 0) for (i in 1 until checks.size) checks[i].isChecked = false
                        else if (checked) checks[0].isChecked = false
                    }
                }
                checks.add(check)
                row.addView(check, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            }
            choices.addView(row)
        }
        guidedDialog = AlertDialog.Builder(this)
            .setTitle("听感反馈（可多选）")
            .setCancelable(false)
            .setView(choices)
            .setNegativeButton("重播") { _, _ -> guidedDialog = null; replayGuidedTest() }
            .setNeutralButton("取消") { _, _ -> cancelGuidedTest() }
            .setPositiveButton("提交", null)
            .create().apply {
                setOnShowListener {
                    getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                        val answers = keys.indices.filter { selected[it] }.map { keys[it] }
                        if (answers.isEmpty()) toast("请至少选择一项，也可以重播后再选")
                        else {
                            guidedFeedback = answers.joinToString(",")
                            dismiss()
                            guidedDialog = null
                            setGuidedPhase("complete")
                            toast("反馈已保存，本轮测试完成")
                        }
                    }
                }
                show()
            }
    }

    private fun failGuidedTest(message: String) {
        CarMicTestRecorder.stop()
        CarMicTestPlayer.stop()
        guidedDialog?.dismiss()
        setGuidedPhase("failed", message)
        guidedDialog = AlertDialog.Builder(this)
            .setTitle("本轮未完成")
            .setMessage(message)
            .setNegativeButton("关闭") { _, _ -> guidedDialog = null }
            .setPositiveButton("重新录制") { _, _ -> guidedDialog = null; prepareGuidedTest() }
            .show()
    }

    private fun cancelGuidedTest() {
        CarMicTestRecorder.stop()
        CarMicTestPlayer.stop()
        guidedDialog?.dismiss()
        guidedDialog = null
        setGuidedPhase("cancelled")
    }

    private fun setGuidedPhase(phase: String, error: String? = null) {
        guidedPhase = phase
        guidedError = error
        phaseStartedMs = android.os.SystemClock.elapsedRealtime()
        publishGuidedState()
        val report = PioneerDebugRuntime.carMicTestState
        getSharedPreferences("pioneer-debug", MODE_PRIVATE).edit()
            .putString("car_mic_guided_result", report).apply()
        PioneerDebugRuntime.log(applicationContext, "MIC_GUIDED $report")
    }

    private fun publishGuidedState() {
        PioneerDebugRuntime.carMicTestState = JSONObject()
            .put("runId", guidedRunId).put("phase", guidedPhase)
            .put("updatedAt", System.currentTimeMillis()).put("fileName", guidedFile)
            .put("captureRate", intent.getIntExtra("captureRate", CarMicTestRecorder.SAMPLE_RATE))
            .put("captureMode", intent.getStringExtra("captureMode") ?: "NORMAL")
            .put("captureSource", intent.getStringExtra("captureSource") ?: "MIC")
            .put("gainDb", intent.getIntExtra("gainDb", 0))
            .put("voiceEffects", intent.getBooleanExtra("voiceEffects", false))
            .put("palMicType", intent.getIntExtra("palMicType", 0))
            .put("playbackStream", intent.getIntExtra("playbackStream", defaultPlaybackStream()))
            .put("standardPlayback", intent.getBooleanExtra("standardPlayback", false))
            .put("stereoPlayback", intent.getBooleanExtra("stereoPlayback", true))
            .put("feedback", guidedFeedback)
            .put("feedbackChoices", org.json.JSONArray(guidedFeedback.split(',').filter { it.isNotBlank() }))
            .put("error", guidedError ?: JSONObject.NULL)
            .put("recording", guidedRecording).put("playback", guidedPlayback).toString()
    }

    private fun refreshHistory(force: Boolean = false) {
        val items = runCatching { CarMicTestStore.recordings(this) }.getOrElse {
            history.removeAllViews()
            history.addView(TextView(this).apply { text = "读取录音历史失败：${it.message}" })
            return
        }
        val signature = items.joinToString("|") {
            "${it.file.name}:${it.file.length()}:${it.verdict}:${it.createdAt}"
        }
        if (!force && signature == historySignature) return
        historySignature = signature
        history.removeAllViews()
        if (items.isEmpty()) {
            history.addView(TextView(this).apply {
                text = "暂无录音"
                textSize = 14f
                setTextColor(0xFF9AA4AE.toInt())
            })
            return
        }
        items.forEachIndexed { index, item ->
            history.addView(buildHistoryRow(index, item))
            if (index < items.lastIndex) {
                history.addView(
                    View(this).apply { setBackgroundColor(0x33FFFFFF) },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        dp(1),
                    ).apply {
                        topMargin = dp(8)
                        bottomMargin = dp(8)
                    },
                )
            }
        }
    }

    private fun buildHistoryRow(index: Int, item: CarMicTestRecording): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(6), 0, dp(6))
        }
        row.addView(TextView(this).apply {
            text = "录音 ${index + 1} · ${item.displayTime} · ${seconds(item.durationMs)} 秒 · ${item.verdict}"
            textSize = 16f
            setTypeface(typeface, Typeface.BOLD)
        })
        row.addView(TextView(this).apply {
            text = buildString {
                append("峰值 ").append(peakDbfs(item.peak))
                append(" · RMS ").append(rmsDbfs(item.rmsDbfs))
                append(" · ").append(item.source).append(' ').append(item.sampleRate).append(" Hz")
                item.routedDeviceType?.let { append(" · 路由 type=").append(it) }
                append('\n').append("入口：").append(gateLabel(item.gate))
                append(" · ").append(item.file.name)
            }
            textSize = 13f
            setTextColor(0xFFB8C2CC.toInt())
            setTextIsSelectable(true)
        })
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(6), 0, 0)
            addView(Button(this@CarMicTestActivity).apply {
                text = "回放"
                setOnClickListener { play(item) }
            })
        })
        return row
    }

    private fun isPlaybackRunning(): Boolean {
        val state = CarMicTestPlayer.snapshot().optString("state")
        return state == "starting" || state == "playing"
    }

    private fun rowParams(): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            1f,
        ).apply { marginEnd = dp(8) }

    private fun seconds(milliseconds: Long): String =
        String.format(Locale.US, "%.1f", milliseconds / 1000.0)

    private fun jsonDbfs(json: JSONObject, key: String): String =
        if (json.isNull(key)) "静音"
        else String.format(Locale.US, "%.1f dBFS", json.optDouble(key))

    private fun peakDbfs(peak: Int): String =
        if (peak <= 0) "静音"
        else String.format(Locale.US, "%.1f dBFS", 20 * log10(peak / 32768.0))

    private fun rmsDbfs(value: Double?): String =
        value?.let { String.format(Locale.US, "%.1f dBFS", it) } ?: "静音"

    private fun gateLabel(gate: String): String = when (gate) {
        "wired-session" -> "有线会话令牌"
        "standalone-vendor" -> "独立车机令牌"
        else -> gate.ifBlank { "未知" }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        const val REQUEST_RECORD_AUDIO = 7_301
    }
}

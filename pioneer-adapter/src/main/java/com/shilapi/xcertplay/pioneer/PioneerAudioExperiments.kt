package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.content.pm.ApplicationInfo
import org.json.JSONObject
import java.io.Closeable

/** In-memory, debug-only controls. Change only between fully stopped sessions/workers. */
object PioneerAudioExperiments {
    data class Settings(
        val revision: Long = 0,
        val testId: String = "BASELINE",
        val micSource: String = "MIC",
        val callMode: String = "COMMUNICATION",
        val micGrantOrder: String = "SOURCE_FIRST",
        val grantDelayMs: Int = 150,
        val sourceDelayMs: Int = 150,
        val ownershipRecovery: Boolean = false,
        val ownershipCheckMs: Int = 1000,
        val ownershipMaxRestores: Int = 3,
        val preferredBuiltin: Boolean = true,
        val softwareAec: String = "DEFAULT",
        val platformEffects: String = "DEFAULT",
        val callStream: Int = 400,
        val callFocus: String = "TRANSIENT",
        val blockCompanionPlay: Boolean = true,
        val muteLocalMedia: Boolean = true,
        val micIdentity: String = "APP",
        val playbackIdentity: String = "DEFAULT",
        val reassertAfterStartMs: Int = 0,
        val callSourcePolicy: String = "MEDIA_300",
        val oversizePcmPacketMs: Int = 10,
    ) {
        fun json(): JSONObject = JSONObject().put("revision", revision).put("testId", testId)
            .put("micSource", micSource).put("callMode", callMode).put("micGrantOrder", micGrantOrder)
            .put("grantDelayMs", grantDelayMs).put("sourceDelayMs", sourceDelayMs)
            .put("ownershipRecovery", ownershipRecovery).put("ownershipCheckMs", ownershipCheckMs)
            .put("ownershipMaxRestores", ownershipMaxRestores).put("preferredBuiltin", preferredBuiltin)
            .put("softwareAec", softwareAec).put("platformEffects", platformEffects)
            .put("callStream", callStream).put("callFocus", callFocus)
            .put("blockCompanionPlay", blockCompanionPlay).put("muteLocalMedia", muteLocalMedia)
            .put("micIdentity", micIdentity).put("playbackIdentity", playbackIdentity).put("reassertAfterStartMs", reassertAfterStartMs).put("callSourcePolicy", callSourcePolicy).put("oversizePcmPacketMs", oversizePcmPacketMs)
    }
    @Volatile private var selected = Settings()
    private var workers = 0
    private val feedback = ArrayDeque<JSONObject>()
    fun current(): Settings = selected
    @Synchronized fun hold(): Closeable {
        workers++
        var closed = false
        return Closeable { synchronized(this) { if (!closed) { closed = true; workers-- } } }
    }
    @Synchronized fun snapshot(): JSONObject = JSONObject().put("configuration", selected.json())
        .put("feedback", org.json.JSONArray(feedback.toList()))
        .put("activeWorkers", workers).put("pioneerSessionActive", PioneerWiredSession.active)
        .put("applyWhen", "STOP_CARPLAY_AND_WAIT_FOR_AUDIO_WORKERS")
        .put("persistence", "PROCESS_ONLY_RESET_ON_APP_RESTART")
        .put("captureRate", "IPHONE_NEGOTIATED_NO_RESAMPLING")
        .put("scope", "PIONEER_CARPLAY_WITH_SHARED_CEAM_GRANT_POLICY")
        .put("recordingTestPage", "OWN_CAPTURE_PARAMETERS_SHARED_CEAM_GRANT_POLICY")

    fun command(context: Context, request: JSONObject): JSONObject {
        check(context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) { "Debug build required" }
        val action = request.optString("action", "STATUS")
        require(action in setOf("STATUS", "SET", "RESET", "FEEDBACK")) { "Unknown audio experiment action" }
        if (action == "STATUS") return snapshot()
        if (action == "FEEDBACK") return synchronized(this) {
            require(request.getString("testId") == selected.testId) { "Feedback testId must match current testId" }
            val value = request.getJSONObject("feedback")
            val keys = value.keys().asSequence().toSet()
            require(keys.isNotEmpty() && keys.all { it in setOf("peerHears", "phoneRoute", "musicStopped", "echo", "pitchWrong", "notes") }) { "Unknown/empty feedback" }
            keys.forEach { key -> require(if (key == "notes") value.get(key) is String && value.getString(key).length <= 512 else value.get(key) is Boolean) { "Wrong feedback type for $key" } }
            val entry = JSONObject().put("testId", selected.testId).put("revision", selected.revision)
                .put("atMs", System.currentTimeMillis()).put("configuration", selected.json()).put("feedback", JSONObject(value.toString()))
            if (feedback.size == 20) feedback.removeFirst()
            feedback.addLast(entry)
            PioneerDebugRuntime.log(context, "Audio experiment feedback: $entry")
            snapshot()
        }
        return synchronized(PioneerWiredSession) {
            synchronized(this) {
                check(!PioneerWiredSession.active && workers == 0) { "Stop CarPlay and wait until activeWorkers=0 before changing audio experiments" }
                val next = if (action == "RESET") Settings(revision = selected.revision + 1) else parse(request.getJSONObject("configuration"))
                selected = next
                PioneerDebugRuntime.log(context, "Audio experiment configured: ${next.json()}")
                snapshot()
            }
        }
    }
    private fun parse(patch: JSONObject): Settings {
        val allowed = Settings().json().keys().asSequence().toSet() - "revision"
        val unknown = patch.keys().asSequence().toSet() - allowed
        require(unknown.isEmpty()) { "Unknown fields: $unknown" }
        require(patch.has("testId")) { "SET requires testId" }
        val merged = selected.json()
        patch.keys().forEach { key ->
            val v = patch.get(key)
            val old = merged.get(key)
            require((old is String && v is String) || (old is Boolean && v is Boolean) ||
                (old is Number && v is Number && v.toDouble().isFinite() && v.toDouble() == v.toInt().toDouble())) { "Wrong type for $key" }
            merged.put(key, v)
        }
        fun choice(key: String, values: Set<String>): String = merged.getString(key).also { require(it in values) { "$key must be one of $values" } }
        fun number(key: String, range: IntRange): Int = merged.getInt(key).also { require(it in range) { "$key must be in $range" } }
        val next = Settings(selected.revision + 1,
            merged.getString("testId").also { require(it.matches(Regex("[A-Za-z0-9_.-]{1,64}"))) { "testId must use 1-64 ASCII letters/digits/_.-" } },
            choice("micSource", setOf("MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION")),
            choice("callMode", setOf("KEEP", "NORMAL", "COMMUNICATION")),
            choice("micGrantOrder", setOf("SOURCE_FIRST", "MIC_FIRST")),
            number("grantDelayMs", 0..1000), number("sourceDelayMs", 0..1000),
            merged.getBoolean("ownershipRecovery"), number("ownershipCheckMs", 250..5000),
            number("ownershipMaxRestores", 1..10), merged.getBoolean("preferredBuiltin"),
            choice("softwareAec", setOf("DEFAULT", "ON", "OFF")),
            choice("platformEffects", setOf("DEFAULT", "ON", "OFF")),
            merged.getInt("callStream").also { require(it in setOf(400, 401, 402)) { "callStream must be 400,401,402" } },
            choice("callFocus", setOf("TRANSIENT", "MIX", "NONE")),
            merged.getBoolean("blockCompanionPlay"), merged.getBoolean("muteLocalMedia"),
            choice("micIdentity", setOf("APP", "WECHAT")), choice("playbackIdentity", setOf("DEFAULT", "APP", "WECHAT")),
            number("reassertAfterStartMs", 0..3000),
            choice("callSourcePolicy", setOf("PRESERVE_PHONE", "MEDIA_300")),
            merged.getInt("oversizePcmPacketMs").also { require(it in setOf(0, 5, 10, 20)) { "oversizePcmPacketMs must be 0,5,10,20" } })
        require(!(next.softwareAec == "ON" && next.platformEffects == "ON")) { "Software and platform AEC cannot both be forced ON" }
        return next
    }
}

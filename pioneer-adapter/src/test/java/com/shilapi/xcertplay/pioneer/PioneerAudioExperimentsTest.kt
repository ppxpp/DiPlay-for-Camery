package com.shilapi.xcertplay.pioneer

import android.content.pm.ApplicationInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerAudioExperimentsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    @Before fun reset() {
        context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE
        PioneerWiredSession.current?.close()
        command("RESET")
    }
    @After fun cleanup() { PioneerWiredSession.current?.close(); context.applicationInfo.flags = context.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE; command("RESET") }
    private fun command(action: String, configuration: String? = null) = PioneerAudioExperiments.command(context,
        JSONObject().put("action", action).also { if (configuration != null) it.put("configuration", JSONObject(configuration)) })

    @Test fun groupedChangeHasReceiptAndResetRestoresBaseline() {
        val reply = command("SET", """{"testId":"call-402-native","micIdentity":"WECHAT","playbackIdentity":"WECHAT","callMode":"NORMAL","ownershipRecovery":false} """)
        assertEquals("call-402-native", reply.getJSONObject("configuration").getString("testId"))
        assertEquals("WECHAT", PioneerAudioExperiments.current().micIdentity)
        command("RESET")
        assertEquals("APP", PioneerAudioExperiments.current().micIdentity)
        assertFalse(PioneerAudioExperiments.current().ownershipRecovery)
        assertEquals(400, PioneerAudioExperiments.current().callStream)
        assertEquals("COMMUNICATION", PioneerAudioExperiments.current().callMode)
        assertEquals(10, PioneerAudioExperiments.current().oversizePcmPacketMs)
    }
    @Test fun invalidOrConflictingFieldsNeverPartiallyApply() {
        val before = PioneerAudioExperiments.current()
        for (patch in listOf("""{"testId":"bad","unknown":true}""",
            """{"testId":"bad","ownershipRecovery":"false"}""",
            """{"testId":"bad","grantDelayMs":1.5}""",
            """{"testId":"bad","softwareAec":"ON","platformEffects":"ON"}""",
            """{"testId":"bad","callStream":403}""",
            """{"testId":"bad","captureRate":16000}""")) {
            try { command("SET", patch); fail(patch) } catch (_: IllegalArgumentException) { }
            assertEquals(before, PioneerAudioExperiments.current())
        }
    }
    @Test fun requiresStoppedSessionAndFullyReleasedWorkers() {
        PioneerAudioExperiments.hold().use {
            try { command("RESET"); fail() } catch (_: IllegalStateException) { }
            assertEquals(1, command("STATUS").getInt("activeWorkers"))
        }
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.VPN, false)
        try { command("RESET"); fail() } catch (_: IllegalStateException) { } finally { session.close() }
        command("RESET")
        assertEquals(0, command("STATUS").getInt("activeWorkers"))
    }
    @Test fun releaseApplicationCannotReadOrChangeDebugStrategies() {
        context.applicationInfo.flags = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
        for (action in listOf("STATUS", "SET", "RESET")) {
            try { command(action, """{"testId":"release"}"""); fail(action) } catch (_: IllegalStateException) { }
        }
    }
    @Test fun feedbackIsBoundToRoundAndCannotBeMislabelled() {
        command("SET", """{"testId":"call-feedback"}""")
        val feedback = JSONObject().put("action", "FEEDBACK").put("testId", "call-feedback")
            .put("feedback", JSONObject().put("peerHears", false).put("musicStopped", true))
        val reply = PioneerAudioExperiments.command(context, feedback)
        val entry = reply.getJSONArray("feedback").getJSONObject(reply.getJSONArray("feedback").length() - 1)
        assertEquals(PioneerAudioExperiments.current().revision, entry.getLong("revision"))
        feedback.put("testId", "old-round")
        try { PioneerAudioExperiments.command(context, feedback); fail() } catch (_: IllegalArgumentException) { }
    }

    @Test fun allSingleDimensionStrategiesValidateAndKeepNegotiatedRate() {
        val values = listOf(
            "micSource" to listOf("MIC", "VOICE_RECOGNITION", "VOICE_COMMUNICATION"),
            "callMode" to listOf("KEEP", "NORMAL", "COMMUNICATION"),
            "micGrantOrder" to listOf("SOURCE_FIRST", "MIC_FIRST"),
            "micIdentity" to listOf("APP", "WECHAT"),
            "playbackIdentity" to listOf("DEFAULT", "APP", "WECHAT"),
            "callFocus" to listOf("TRANSIENT", "MIX", "NONE"))
        for ((key, choices) in values) for (choice in choices) {
            val patch = JSONObject().put("testId", "dimension-test").put(key, choice)
            command("SET", patch.toString())
            assertEquals(choice, command("STATUS").getJSONObject("configuration").getString(key))
        }
        assertEquals("IPHONE_NEGOTIATED_NO_RESAMPLING", command("STATUS").getString("captureRate"))
    }

}

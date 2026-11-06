package com.shilapi.xcertplay.pioneer

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE, shadows = [PioneerApplicationControlReleaseTest.SessionShadow::class])
@ConscryptMode(ConscryptMode.Mode.OFF)
class PioneerApplicationControlReleaseTest {
    companion object {
        var requests = 0
        var reply = JSONObject()
        var action = ""
        var failure = false
        var invalidate: (() -> Unit)? = null
    }
    @Implements(PioneerRootSession::class)
    class SessionShadow {
        companion object {
            @JvmStatic @Implementation fun request(context: Context, operation: String, payload: JSONObject, timeout: Long,
                                        current: () -> Boolean): JSONObject {
                assertEquals("APPLICATIONS", operation); assertEquals(10000L, timeout)
                check(current()); requests++; action = payload.getString("action")
                invalidate?.invoke()
                if (failure) throw PioneerRootIpc.CompletionUnknown("lost reply")
                return reply
            }
        }
    }
    private val app get() = RuntimeEnvironment.getApplication()
    private fun states() = JSONArray().apply {
        PioneerManagedApplication.values().forEach { put(JSONObject().put("target", it.id).put("state", "ENABLED")) }
    }
    @Before fun setup() {
        requests = 0; failure = false; invalidate = null
        PioneerUsbPolicy.setMode(app, "ON"); reply = JSONObject().put("apps", states())
    }
    @After fun cleanup() { PioneerUsbPolicy.setMode(app, "AUTO") }
    @Test fun statusRequiresCompleteUniqueAllowlistedKnownStates() {
        assertEquals(3, PioneerApplicationControl.read(app) { true }.size)
        val invalid = listOf(JSONArray(), states().put(states().getJSONObject(0)),
            states().put(0, JSONObject().put("target", "android").put("state", "ENABLED")),
            states().put(0, JSONObject().put("target", "amap").put("state", "garbage")))
        invalid.forEach { reply = JSONObject().put("apps", it)
            assertThrows(Exception::class.java) { PioneerApplicationControl.read(app) { true } } }
    }
    @Test fun staleReplyAndDisabledAdaptationCannotReturnActionableState() {
        var current = true; invalidate = { current = false }
        assertThrows(IllegalStateException::class.java) { PioneerApplicationControl.read(app) { current } }
        current = true; invalidate = { PioneerUsbPolicy.setMode(app, "OFF") }
        assertThrows(IllegalStateException::class.java) { PioneerApplicationControl.read(app) { current } }
        val before = requests
        assertThrows(IllegalStateException::class.java) { PioneerApplicationControl.setDisabled(app, PioneerManagedApplication.AMAP, true) { true } }
        assertEquals(before, requests)
    }
    @Test fun storedOffBlocksManagementEvenWhenWiredSessionKeepsAppliedOn() {
        val session = PioneerWiredSession.begin(PioneerWiredSession.Backend.WIRELESS, true)
        try {
            PioneerUsbPolicy.setMode(app, "OFF")
            assertTrue(PioneerUsbPolicy.enabled(app))
            assertThrows(IllegalStateException::class.java) {
                PioneerApplicationControl.setDisabled(app, PioneerManagedApplication.AMAP, true) { true }
            }
            assertEquals(0, requests)
        } finally { session.close() }
    }
    @Test fun mutationReplyMustConfirmRequestedStateOrAbsence() {
        for (state in listOf("ENABLED", "UNKNOWN")) {
            reply = JSONObject().put("app", JSONObject().put("target", "amap").put("state", state))
            assertThrows(IllegalStateException::class.java) {
                PioneerApplicationControl.setDisabled(app, PioneerManagedApplication.AMAP, true) { true }
            }
        }
        reply = JSONObject().put("app", JSONObject().put("target", "amap").put("state", "NOT_INSTALLED"))
        assertEquals(PioneerApplicationControl.State.NOT_INSTALLED,
            PioneerApplicationControl.setDisabled(app, PioneerManagedApplication.AMAP, true) { true })
    }
    @Test fun uncertainSetIsSentOnceAndMismatchedTargetRejected() {
        failure = true
        assertThrows(PioneerRootIpc.CompletionUnknown::class.java) {
            PioneerApplicationControl.setDisabled(app, PioneerManagedApplication.AMAP, true) { true }
        }
        assertEquals(1, requests); assertEquals("SET", action)
        failure = false
        reply = JSONObject().put("app", JSONObject().put("target", "tencent_map").put("state", "DISABLED"))
        assertThrows(IllegalStateException::class.java) {
            PioneerApplicationControl.setDisabled(app, PioneerManagedApplication.AMAP, true) { true }
        }
        assertEquals(2, requests)
    }
}

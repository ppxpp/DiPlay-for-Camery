package com.shilapi.xcertplay.pioneer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerHudDiagnosticsTest {
    @Test fun samplesSteadyStateButPreservesImmediateReasonChanges() {
        val history = HudDiagnosticHistory()
        val state = JSONObject().put("reason", "waiting_for_approach")
        history.add(state, 0, 1000)
        history.add(state, 100, 1100)
        state.put("reason", "oem_navigation_ownership_unconfirmed")
        history.add(state, 200, 1200)
        val lines = history.export(200).lines()
        assertEquals(2, JSONObject(lines.first()).getInt("entries"))
        assertEquals("waiting_for_approach", JSONObject(lines[1]).getJSONObject("snapshot").getString("reason"))
        assertEquals("oem_navigation_ownership_unconfirmed", JSONObject(lines[2]).getJSONObject("snapshot").getString("reason"))
    }
    @Test fun prunesOldStateAndBoundsRapidTransitions() {
        val history = HudDiagnosticHistory()
        repeat(1000) { history.add(JSONObject().put("reason", "reason-$it"), it.toLong(), it.toLong()) }
        assertEquals(256, JSONObject(history.export(1000).lineSequence().first()).getInt("entries"))
        assertEquals(0, JSONObject(history.export(122_000).lineSequence().first()).getInt("entries"))
    }
    @Test fun serializedHistoryIsIndependentOfLaterCallerMutation() {
        val history = HudDiagnosticHistory()
        val state = JSONObject().put("lastOutput", JSONObject().put("result", "failed").put("exceptionChain", "root cause"))
        history.add(state, 0, 0)
        state.getJSONObject("lastOutput").put("result", "sdk_accepted")
        assertEquals("failed", JSONObject(history.export(0).lines()[1]).getJSONObject("snapshot")
            .getJSONObject("lastOutput").getString("result"))
    }
}

package com.shilapi.xcertplay.pioneer

import android.content.Context
import android.os.SystemClock
import org.json.JSONObject

/** Actual PackageManager state, never a saved desired state that could disable a later reinstall. */
object PioneerApplicationControl {
    enum class State { ENABLED, DISABLED, NOT_INSTALLED, UNKNOWN }

    fun read(context: Context, current: () -> Boolean): Map<PioneerManagedApplication, State> {
        val response = request(context, JSONObject().put("action", "STATUS"), current).getJSONArray("apps")
        val states = mutableMapOf<PioneerManagedApplication, State>()
        for (index in 0 until response.length()) {
            val entry = response.getJSONObject(index)
            val target = PioneerManagedApplication.fromId(entry.getString("target"))
            check(states.put(target, State.valueOf(entry.getString("state"))) == null)
        }
        check(states.size == PioneerManagedApplication.values().size)
        return states
    }

    fun setDisabled(context: Context, target: PioneerManagedApplication, disabled: Boolean,
                    current: () -> Boolean): State {
        val result = request(context, JSONObject().put("action", "SET").put("target", target.id)
            .put("disabled", disabled), current).getJSONObject("app")
        check(result.getString("target") == target.id)
        val state = State.valueOf(result.getString("state"))
        check(state == State.NOT_INSTALLED || state == (if (disabled) State.DISABLED else State.ENABLED)) {
            "APPLICATION_STATE_NOT_APPLIED"
        }
        return state
    }

    private fun request(context: Context, payload: JSONObject, current: () -> Boolean): JSONObject {
        check(PioneerUsbPolicy.enabled(context) && CamryAdaptation.enabled(context)) { "CAMRY_ADAPTATION_REQUIRED" }
        val until = SystemClock.elapsedRealtime() + 20_000
        val isCurrent = {
            current() && PioneerUsbPolicy.enabled(context) && CamryAdaptation.enabled(context) &&
                SystemClock.elapsedRealtime() < until
        }
        check(isCurrent()) { "APPLICATION_REQUEST_STALE" }
        val result = PioneerRootSession.request(context.applicationContext, "APPLICATIONS", payload, 10_000, isCurrent)
        check(isCurrent()) { "APPLICATION_REQUEST_STALE" }
        return result
    }
}

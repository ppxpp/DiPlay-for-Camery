package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import android.content.Context
import com.shilapi.xcertplay.pioneer.PioneerRootSession
import org.json.JSONObject

/** Requests are never replayed after submission: the shared root owns execution and cancellation. */
internal object HotspotRootWorker {
    fun execute(context: Context, payload: JSONObject, current: () -> Boolean): JSONObject =
        PioneerRootSession.request(context, "HOTSPOT", payload, 180_000, current)
}

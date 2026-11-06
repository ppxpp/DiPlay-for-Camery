package com.shilapi.xcertplay.pioneer

import android.content.Context
import org.json.JSONObject

/** Read-only diagnostics share the root service; no shell dispatch, temp scripts, or result polling. */
object PioneerDiagnosticReader {
    data class Item(val name: String, val status: String, val text: String)
    @Synchronized fun collect(context: Context): List<Item> {
        if (!CamryAdaptation.enabled()) return listOf(Item("root", "unavailable", "Camry adaptation disabled"))
        return PioneerRootDiagnostics.names().map { name ->
            runCatching {
                val result = PioneerRootSession.request(context, "DIAGNOSTIC", JSONObject().put("name", name), 5000)
                Item(name, result.getString("status"), result.getString("text"))
            }.getOrElse { Item(name, "unavailable", it.message.orEmpty()) }
        }
    }
}

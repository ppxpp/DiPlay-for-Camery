package com.shilapi.xcertplay

import org.json.JSONArray
import org.json.JSONObject

/** Preserve typed diagnostic verdicts and SHA-256, without bypassing scalar privacy filtering. */
internal object HudDiagnosticJson {
    private val privateKey = Regex("(?i)(password|passphrase|token|private.?key|certificate|pair.?record|ssid|payload)|^(pass|body|road|roadName|coordinates|latitude|longitude)$")
    fun sanitize(source: JSONObject): JSONObject = JSONObject().also { target ->
        source.keys().forEach { key ->
            if (!privateKey.containsMatchIn(key)) target.put(key, clean(key, source.get(key)))
        }
    }
    private fun clean(key: String, value: Any?): Any = when (value) {
        is JSONObject -> sanitize(value)
        is JSONArray -> JSONArray().also { result -> for (i in 0 until value.length()) result.put(clean(key, value.get(i))) }
        is String -> if (key in setOf("expectedSha256", "actualSha256") && value.matches(Regex("[0-9a-fA-F]{64}"))) value
            else if (key == "status" && value in setOf("pass", "mismatch", "read_failed")) value
            else DiagnosticRedactor.redactField(value)
        null -> JSONObject.NULL
        else -> value
    }
}

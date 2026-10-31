package com.shilapi.xcertplay.orchestration.progress

import com.shilapi.xcertplay.network.WirelessStartupException
import com.shilapi.xcertplay.network.WirelessStartupFailure
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.TimeoutException

/** Structured facts only. Exception messages can contain addresses, names and credentials. */
data class ConnectionFailure(
    val code: String,
    val stage: String,
    val timeoutKind: String? = null,
    val thresholdMillis: Long? = null,
)

class ConnectionTimeoutException(
    val timeoutStage: String,
    val thresholdMillis: Long,
    val timeoutKind: String = "stage_deadline",
    message: String,
) : IOException(message)

fun connectionFailure(error: Throwable, stage: ConnectionStep): ConnectionFailure {
    val causes = generateSequence(error) { it.cause }.take(12).toList()
    causes.filterIsInstance<ConnectionTimeoutException>().firstOrNull()?.let {
        return ConnectionFailure("${it.timeoutStage}_timeout", it.timeoutStage, it.timeoutKind, it.thresholdMillis)
    }
    causes.filterIsInstance<WirelessStartupException>().firstOrNull()?.let {
        val failedStage = when (it.reason) {
            WirelessStartupFailure.FIRST_TCP_TIMEOUT -> "wireless-join"
            WirelessStartupFailure.BLUETOOTH_NOT_CONNECTED, WirelessStartupFailure.BLUETOOTH_UNUSABLE,
            WirelessStartupFailure.BLUETOOTH_CONFIGURATION_CONFLICT -> "device"
            else -> "network"
        }
        val timeout = it.timeoutMillis ?: if (it.reason == WirelessStartupFailure.FIRST_TCP_TIMEOUT) 30_000L else null
        return ConnectionFailure(it.reason.name.lowercase(), failedStage,
            if (timeout != null) "stage_deadline" else null, timeout)
    }
    if (causes.any { it is SocketTimeoutException || it is TimeoutException || it is com.shilapi.xcertplay.transport.IphoneUsbException.TimedOut })
        return ConnectionFailure("${stage.key}_timeout", stage.key, "transport_deadline")
    return ConnectionFailure(if (causes.any { it is SecurityException }) "permission_denied" else "unknown", stage.key)
}

data class ConnectionMeasuredTask(val id: String, val durationMillis: Long, val result: String)

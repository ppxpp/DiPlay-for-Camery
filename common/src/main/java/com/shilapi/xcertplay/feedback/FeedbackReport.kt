package com.shilapi.xcertplay.feedback

import android.content.Context
import android.os.Build
import com.shilapi.xcertplay.AirPlayPersistence
import com.shilapi.xcertplay.BluetoothDiagnosticSnapshot
import com.shilapi.xcertplay.CarPlayBackgroundSession
import com.shilapi.xcertplay.CrashRecord
import com.shilapi.xcertplay.DiagnosticRedactor
import com.shilapi.xcertplay.DiPlayPreferences
import com.shilapi.xcertplay.DisplayDiagnosticSnapshot
import com.shilapi.xcertplay.ProcessExitDiagnostics
import com.shilapi.xcertplay.StartupDiagnosticSnapshot
import com.shilapi.xcertplay.WheelKeyLog
import com.shilapi.xcertplay.WheelKeyService
import com.shilapi.xcertplay.transport.UsbSessionDiagnostics
import com.shilapi.xcertplay.update.UpdateAvailability
import java.util.Date

/** The small diagnostic summary shipped with a feedback bundle. */
internal object FeedbackReport {
    fun build(context: Context, meta: FeedbackMeta): String {
        val app = context.applicationContext
        val builder = StringBuilder()
        builder.appendLine("DiPlay feedback")
        builder.appendLine("Created: ${Date(meta.createdAt)}")
        builder.appendLine("App: ${meta.appVersionName} (${meta.appVersionCode}) channel=${meta.updateChannel}")
        builder.appendLine("Category: ${meta.category.wireName} entry=${meta.entry.wireName}")
        meta.failureStage?.takeIf { it.isNotBlank() }?.let { builder.appendLine("Failure stage: $it") }
        builder.appendLine("Android: ${Build.VERSION.RELEASE} API ${Build.VERSION.SDK_INT}")
        builder.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} board=${Build.BOARD}")
        builder.appendLine("Connection mode: ${if (AirPlayPersistence.loadWirelessEnabled(app)) "wireless" else "USB"}")
        builder.appendLine("Session: ${when {
            CarPlayBackgroundSession.active -> "active"
            CarPlayBackgroundSession.hasSession() -> "connecting"
            else -> "stopped"
        }}")
        builder.appendLine("Phone configured: ${DiPlayPreferences.phoneAddress(app) != null}")
        builder.appendLine()
        builder.appendLine("Saved display: HEVC=${AirPlayPersistence.loadHevcEnabled(app)} " +
            "fps=${AirPlayPersistence.loadFps(app)} " +
            "widthMm=${AirPlayPersistence.loadWidthPhysicalMm(app)} " +
            "scalePercent=${AirPlayPersistence.loadDisplayScalePercent(app)}")
        AirPlayPersistence.loadUsbChargingCurrent(app).let {
            builder.appendLine("USB charging offer: ${it.key} (${it.milliAmps} mA)")
        }
        builder.appendLine()
        builder.appendLine(BluetoothDiagnosticSnapshot.report(app))
        builder.appendLine()
        builder.appendLine("--- Last display negotiation ---")
        builder.appendLine(DisplayDiagnosticSnapshot.report(app))
        builder.appendLine()
        builder.appendLine("--- Last boot and app-launch result ---")
        builder.appendLine(StartupDiagnosticSnapshot.report(app))
        builder.appendLine()
        builder.appendLine("--- Wheel keys ---")
        builder.appendLine(WheelKeyLog.report(WheelKeyService.connected()).joinToString("\n"))
        builder.appendLine()
        builder.appendLine("--- Update status ---")
        builder.appendLine(UpdateAvailability.report(app, System.currentTimeMillis()))
        builder.appendLine()
        builder.appendLine("--- Recent own-app process exits (Android 11+) ---")
        builder.appendLine(ProcessExitDiagnostics.report(app))
        builder.appendLine()
        builder.appendLine("--- Last crash recorded by DiPlay ---")
        builder.appendLine(CrashRecord.report(app))
        builder.appendLine()
        builder.appendLine("--- USB configuration and claim verdict ---")
        builder.appendLine(UsbSessionDiagnostics.report())
        return builder.lineSequence()
            .mapNotNull(DiagnosticRedactor::redact)
            .joinToString("\n")
    }
}

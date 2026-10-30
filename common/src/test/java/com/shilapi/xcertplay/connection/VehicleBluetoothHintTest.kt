package com.shilapi.xcertplay.connection

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.shilapi.xcertplay.host.R
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.progress.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[25],manifest=Config.NONE)
class VehicleBluetoothHintTest {
    private val app get()=RuntimeEnvironment.getApplication()
    private fun progress()=ConnectionProgress("test",true,channel=ConnectionChannel.PIONEER_WIRELESS)
    private fun texts(view: View): List<String> = when(view) {
        is TextView -> listOf(view.text.toString())
        is ViewGroup -> (0 until view.childCount).flatMap { texts(view.getChildAt(it)) }
        else -> emptyList()
    }
    @Test fun waitingHintClearsWhenBluetoothConnects() {
        val p=progress()
        p.status(CarPlayStatus.WaitingVehicleBluetooth)
        val panel=ConnectionProgressPanel(app).apply { bind(p) }
        val hint=app.getString(R.string.vehicle_bluetooth_connect_hint)
        assertTrue(texts(panel).any { it.contains(hint) })
        p.status(CarPlayStatus.ConnectingBluetooth)
        panel.bind(p)
        assertFalse(texts(panel).any { it.contains(hint) })
    }
    @Test fun missingBluetoothHasAnInlineFailureHint() {
        val p=progress()
        p.failWirelessStep(ConnectionStep.DEVICE,"vehicle_bluetooth_not_connected")
        val panel=ConnectionProgressPanel(app).apply { bind(p) }
        assertTrue(texts(panel).any { it.contains(app.getString(R.string.vehicle_bluetooth_missing_hint)) })
    }
    @Test fun anotherFailureIsNotMisreportedAsBluetoothDisconnected() {
        val p=progress()
        p.failWirelessStep(ConnectionStep.DEVICE)
        val panel=ConnectionProgressPanel(app).apply { bind(p) }
        assertFalse(texts(panel).any { it.contains(app.getString(R.string.vehicle_bluetooth_missing_hint)) })
    }
    @Test fun wifiReadinessHintTracksTheNetworkPreparationTask() {
        val p=progress()
        p.task(ConnectionTask.WIRELESS_NETWORK,ConnectionTaskState.RUNNING,"vehicle_wifi_enabling")
        val panel=ConnectionProgressPanel(app).apply { bind(p) }
        val enabling=app.getString(R.string.connect_progress_wifi_enabling)
        val waiting=app.getString(R.string.connect_progress_wifi_wait)
        assertTrue(texts(panel).any { it.contains(enabling) })

        p.task(ConnectionTask.WIRELESS_NETWORK,ConnectionTaskState.RUNNING,"vehicle_wifi_wait")
        panel.bind(p)
        assertTrue(texts(panel).any { it.contains(waiting) })

        p.task(ConnectionTask.WIRELESS_NETWORK,ConnectionTaskState.COMPLETE)
        panel.bind(p)
        assertFalse(texts(panel).any { it.contains(enabling) || it.contains(waiting) })
    }
}

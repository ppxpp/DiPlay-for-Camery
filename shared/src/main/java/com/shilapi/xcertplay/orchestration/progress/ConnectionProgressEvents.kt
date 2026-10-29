package com.shilapi.xcertplay.orchestration.progress

import com.shilapi.xcertplay.orchestration.CarPlayStatus

/** Maps typed lifecycle events rather than parsing translated status/log strings. */
fun ConnectionProgress.status(status: CarPlayStatus) {
    if(channel == ConnectionChannel.PIONEER_WIRELESS) {
        when(status) {
            CarPlayStatus.ConnectingBluetooth -> task(ConnectionTask.WIRELESS_LINK,ConnectionTaskState.RUNNING)
            CarPlayStatus.WaitingVehicleBluetooth -> task(ConnectionTask.WIRELESS_LINK,ConnectionTaskState.RUNNING,"vehicle_bluetooth_wait")
            CarPlayStatus.StartingHotspot -> task(ConnectionTask.WIRELESS_NETWORK,ConnectionTaskState.RUNNING)
            is CarPlayStatus.HotspotReady -> task(ConnectionTask.WIRELESS_NETWORK,ConnectionTaskState.COMPLETE)
            is CarPlayStatus.Failed, CarPlayStatus.ControlEnded -> stop(ConnectionOutcome.FAILED)
            else -> Unit
        }
        return
    }
    transportTasks()
    when (status) {
        CarPlayStatus.DiscoveringIphone -> task(ConnectionTask.DETECT, ConnectionTaskState.RUNNING)
        CarPlayStatus.RequestingIphonePermission -> {
            task(ConnectionTask.PERMISSION_REQUEST, ConnectionTaskState.RUNNING)
            task(ConnectionTask.USB_PERMISSION, ConnectionTaskState.RUNNING)
        }
        CarPlayStatus.OpeningDataPaths -> task(ConnectionTask.USB_OPEN, ConnectionTaskState.RUNNING)
        CarPlayStatus.Pairing -> {
            task(ConnectionTask.PAIR, ConnectionTaskState.RUNNING)
            if (wireless) {
                task(ConnectionTask.DETECT, ConnectionTaskState.COMPLETE)
                task(ConnectionTask.WIRELESS_LINK, ConnectionTaskState.COMPLETE)
            }
        }
        CarPlayStatus.ConnectingControl -> {
            task(ConnectionTask.PAIR, ConnectionTaskState.COMPLETE)
            task(ConnectionTask.AUTHENTICATE, ConnectionTaskState.RUNNING)
        }
        CarPlayStatus.RunningWireless, CarPlayStatus.RunningControl -> task(ConnectionTask.AUTHENTICATE, ConnectionTaskState.COMPLETE)
        CarPlayStatus.StartingHotspot -> task(ConnectionTask.WIRELESS_NETWORK, ConnectionTaskState.RUNNING)
        is CarPlayStatus.HotspotReady -> task(ConnectionTask.WIRELESS_NETWORK, ConnectionTaskState.COMPLETE)
        CarPlayStatus.ConnectingBluetooth, CarPlayStatus.WaitingVehicleBluetooth -> {
            task(ConnectionTask.DETECT, ConnectionTaskState.RUNNING)
            task(ConnectionTask.WIRELESS_LINK, ConnectionTaskState.RUNNING)
        }
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> task(ConnectionTask.CHANNEL_READY, ConnectionTaskState.COMPLETE)
        else -> Unit
    }
    when (status) {
        CarPlayStatus.DiscoveringMfi, CarPlayStatus.MfiReady -> step(ConnectionStep.PREPARATION)
        CarPlayStatus.WaitingForMfi, CarPlayStatus.RequestingMfiPermission -> step(ConnectionStep.PREPARATION, true)
        CarPlayStatus.DiscoveringIphone, CarPlayStatus.WaitingForReenumeration,
        CarPlayStatus.SelectingConfiguration, CarPlayStatus.OpeningDataPaths,
        CarPlayStatus.ConnectingBluetooth -> step(ConnectionStep.DEVICE)
        CarPlayStatus.WaitingForIphone, CarPlayStatus.RequestingIphonePermission,
        CarPlayStatus.WaitingForPairedIphone, CarPlayStatus.WaitingVehicleBluetooth -> step(ConnectionStep.DEVICE, true)
        CarPlayStatus.StartingHotspot, is CarPlayStatus.HotspotReady,
        CarPlayStatus.AttachingNetwork -> step(ConnectionStep.NETWORK)
        CarPlayStatus.Pairing, CarPlayStatus.ConnectingControl,
        CarPlayStatus.RunningWireless, CarPlayStatus.RunningControl -> step(ConnectionStep.CONTROL)
        CarPlayStatus.WirelessActive, CarPlayStatus.WirelessActiveFallback -> sessionRequested()
        CarPlayStatus.ControlEnded, is CarPlayStatus.Failed -> stop(ConnectionOutcome.FAILED)
    }
}

fun ConnectionProgress.transportTasks() {
    if(channel == ConnectionChannel.PIONEER_WIRELESS)return
    com.shilapi.xcertplay.pioneer.PioneerConnectionTasks.snapshot().forEach { event ->
        val task = ConnectionTask.entries.firstOrNull { it.id == event.id } ?: return@forEach
        val value = runCatching { ConnectionTaskState.valueOf(event.state) }.getOrNull() ?: return@forEach
        task(task, value, event.detail, event.atMillis, event.startedAtMillis)
    }
}

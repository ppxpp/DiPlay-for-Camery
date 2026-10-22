package com.shilapi.xcertplay.orchestration.progress

import org.junit.Assert.*
import org.junit.Test

class ConnectionTaskProgressTest {
    @Test fun usbCanCompleteWhileVpnIsStillPendingAndFailureDoesNotCompleteOtherTasks() {
        var now = 100L
        val model = ConnectionProgress("vpn", false, clock = { now }, channel = ConnectionChannel.VPN)
        model.task(ConnectionTask.USB_PERMISSION, ConnectionTaskState.COMPLETE)
        now = 120
        model.task(ConnectionTask.USB_OPEN, ConnectionTaskState.RUNNING)
        val state = model.snapshot().tasks.associateBy { it.task }
        assertEquals(ConnectionTaskState.COMPLETE, state.getValue(ConnectionTask.USB_PERMISSION).state)
        assertEquals(ConnectionTaskState.PENDING, state.getValue(ConnectionTask.VPN_PERMISSION).state)
        now = 150
        model.stop(ConnectionOutcome.FAILED)
        assertEquals(ConnectionTaskState.FAILED, model.snapshot().tasks.first { it.task == ConnectionTask.USB_OPEN }.state)
        model.task(ConnectionTask.USB_OPEN, ConnectionTaskState.RUNNING, at = 120)
        assertEquals(ConnectionTaskState.FAILED, model.snapshot().tasks.first { it.task == ConnectionTask.USB_OPEN }.state)
    }
    @Test fun templatesExcludeTasksForOtherDataPlanes() {
        val lwip = connectionTasks(ConnectionChannel.LWIP)
        assertTrue(ConnectionTask.STACK in lwip)
        assertFalse(ConnectionTask.TUN in lwip)
        assertFalse(ConnectionTask.VPN_PERMISSION in lwip)
        assertFalse(ConnectionTask.RULES in lwip)
        val native = connectionTasks(ConnectionChannel.NATIVE_NCM)
        assertTrue(ConnectionTask.DRIVER in native)
        assertFalse(ConnectionTask.BRIDGE in native)
        val wireless = connectionTasks(ConnectionChannel.WIRELESS)
        assertTrue(ConnectionTask.WIRELESS_LINK in wireless)
        assertFalse(ConnectionTask.USB_PERMISSION in wireless)
        assertFalse(ConnectionTask.ROOT in wireless)
    }
    @Test fun completedTaskReplayPreservesStartupTimeBeforeUiExisted() {
        val model = ConnectionProgress("vpn",false,clock = { 500L },channel = ConnectionChannel.VPN)
        model.task(ConnectionTask.ROOT,ConnectionTaskState.COMPLETE,at = 400,startedAt = 100)
        assertEquals(300L,model.snapshot().tasks.first { it.task == ConnectionTask.ROOT }.elapsedMillis)
    }
    @Test fun reusedPermissionAndConcurrentRootTaskKeepIndependentDurations() {
        var now = 100L
        val model = ConnectionProgress("vpn", false, clock = { now }, channel = ConnectionChannel.VPN)
        model.task(ConnectionTask.ROOT, ConnectionTaskState.RUNNING)
        now = 110
        model.task(ConnectionTask.USB_PERMISSION, ConnectionTaskState.REUSED)
        now = 150
        val state = model.snapshot().tasks.associateBy { it.task }
        assertEquals(50L, state.getValue(ConnectionTask.ROOT).elapsedMillis)
        assertEquals(0L, state.getValue(ConnectionTask.USB_PERMISSION).elapsedMillis)
        assertEquals(ConnectionTaskState.RUNNING, state.getValue(ConnectionTask.ROOT).state)
        assertEquals(ConnectionTaskState.REUSED, state.getValue(ConnectionTask.USB_PERMISSION).state)
    }
}

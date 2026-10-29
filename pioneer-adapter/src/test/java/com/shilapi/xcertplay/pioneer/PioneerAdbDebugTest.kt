package com.shilapi.xcertplay.pioneer

import org.junit.Assert.*
import org.junit.Test

class PioneerAdbDebugTest {
    @Test fun enabledRequiresBothUsbAndDaemon() {
        assertEquals(true, PioneerAdbDebug.actualState("mtp,adb", "running"))
        assertEquals(true, PioneerAdbDebug.actualState("adb", "running"))
    }

    @Test fun disabledRequiresBothUsbAndDaemon() {
        assertEquals(false, PioneerAdbDebug.actualState("mtp", "stopped"))
    }

    @Test fun transitionalAndUnavailableStatesAreNeverPresentedAsConfirmed() {
        assertNull(PioneerAdbDebug.actualState("mtp,adb", "stopped"))
        assertNull(PioneerAdbDebug.actualState("mtp", "running"))
        assertNull(PioneerAdbDebug.actualState("", ""))
        assertNull(PioneerAdbDebug.actualState("adb", "restarting"))
        assertNull(PioneerAdbDebug.actualState("notadb", "running"))
    }
}

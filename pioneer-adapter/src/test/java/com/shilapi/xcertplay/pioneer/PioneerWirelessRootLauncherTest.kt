package com.shilapi.xcertplay.pioneer

import org.junit.Assert.*
import org.junit.Test

class PioneerWirelessRootLauncherTest {
    @Test fun turningOffBlocksNewRepairsButAllowsAnExistingOwnersCleanup() {
        assertFalse(PioneerWirelessRootLauncher.callbackAllowed("--repair",false,true))
        assertFalse(PioneerWirelessRootLauncher.callbackAllowed("--audit",false,false))
        assertTrue(PioneerWirelessRootLauncher.callbackAllowed("--audit",false,true))
        assertTrue(PioneerWirelessRootLauncher.callbackAllowed("--clear-idle",false,true))
        assertTrue(PioneerWirelessRootLauncher.callbackAllowed("--repair",true,false))
        assertFalse(PioneerWirelessRootLauncher.callbackAllowed("unknown",true,true))
    }
}

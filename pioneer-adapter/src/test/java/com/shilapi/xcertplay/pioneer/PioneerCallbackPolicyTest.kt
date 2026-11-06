package com.shilapi.xcertplay.pioneer

import org.junit.Assert.*
import org.junit.Test

class PioneerCallbackPolicyTest {
    @Test fun turningOffBlocksNewRepairsButAllowsAnExistingOwnersCleanup() {
        assertFalse(PioneerCallbackPolicy.callbackAllowed("--repair",false,true))
        assertFalse(PioneerCallbackPolicy.callbackAllowed("--audit",false,false))
        assertTrue(PioneerCallbackPolicy.callbackAllowed("--audit",false,true))
        assertTrue(PioneerCallbackPolicy.callbackAllowed("--clear-idle",false,true))
        assertTrue(PioneerCallbackPolicy.callbackAllowed("--repair",true,false))
        assertFalse(PioneerCallbackPolicy.callbackAllowed("unknown",true,true))
    }
}

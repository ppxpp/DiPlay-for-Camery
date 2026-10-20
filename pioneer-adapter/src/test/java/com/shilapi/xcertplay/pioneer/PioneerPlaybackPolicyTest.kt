package com.shilapi.xcertplay.pioneer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test

class PioneerPlaybackPolicyTest {
    @After fun tearDown() { PioneerWiredSession.current?.close() }

    @Test fun nativeWiredRetainsTheVerifiedNightModeCompatibility() {
        for (backend in PioneerWiredSession.Backend.entries) {
            PioneerWiredSession.begin(backend, verifiedDevice = true).use {
                assertFalse(PioneerPlaybackPolicy.appearanceDeclarationsEnabled)
                assertFalse(PioneerPlaybackPolicy.audioClockTrimEnabled)
            }
        }
    }

    @Test fun wirelessAndOtherHeadUnitsRetainAppearanceDeclarations() {
        assertTrue(PioneerPlaybackPolicy.appearanceDeclarationsEnabled)
        assertTrue(PioneerPlaybackPolicy.audioClockTrimEnabled)
        for (backend in PioneerWiredSession.Backend.entries) {
            PioneerWiredSession.begin(backend, verifiedDevice = false).use {
                assertTrue(PioneerPlaybackPolicy.appearanceDeclarationsEnabled)
                assertTrue(PioneerPlaybackPolicy.audioClockTrimEnabled)
            }
        }
    }
}

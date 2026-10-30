package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PioneerProfilePeersTest {
    @Test fun permitsOnlyDisjointOrdinaryPeerProfiles() {
        assertTrue(PioneerProfilePeers.compatible(emptyMap(),131))
        assertTrue(PioneerProfilePeers.compatible(mapOf("AA" to 0,"BB" to 4),131))
        assertFalse(PioneerProfilePeers.compatible(mapOf("AA" to 1),131))
        assertFalse(PioneerProfilePeers.compatible(mapOf("AA" to 128),131))
        assertFalse(PioneerProfilePeers.compatible(mapOf("AA" to 131),131))
    }

    @Test fun rejectsUnknownTargetOrPeerMasks() {
        assertFalse(PioneerProfilePeers.compatible(emptyMap(),256))
        assertFalse(PioneerProfilePeers.compatible(mapOf("AA" to -1),131))
        assertFalse(PioneerProfilePeers.compatible(mapOf("AA" to 300),131))
    }
}

package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HotspotRequestIdentityTest {
    @get:Rule val folder=TemporaryFolder()
    private fun stat(start: String)="42 (worker name) "+(List(19) { "0" }+start).joinToString(" ")
    @Test fun neverDispatchedRequestDoesNotBlockRecovery() {
        assertFalse(HotspotRequestIdentity.running(File(folder.root,"absent")) { error("must not inspect processes") })
    }
    @Test fun tracksActualProcessAndDetectsPidReuse() {
        val marker=folder.newFile().apply { writeText("42:123") }
        assertTrue(HotspotRequestIdentity.running(marker) { stat("123") })
        assertFalse(HotspotRequestIdentity.running(marker) { stat("124") })
        assertFalse(HotspotRequestIdentity.running(marker) { null })
    }
    @Test(expected=IllegalStateException::class) fun malformedIdentityBlocksRecovery() {
        HotspotRequestIdentity.running(folder.newFile().apply { writeText("unknown") }) { null }
    }
    @Test(expected=SecurityException::class) fun unreadableLiveProcessBlocksRecovery() {
        HotspotRequestIdentity.running(folder.newFile().apply { writeText("42:123") }) { throw SecurityException("denied") }
    }
}

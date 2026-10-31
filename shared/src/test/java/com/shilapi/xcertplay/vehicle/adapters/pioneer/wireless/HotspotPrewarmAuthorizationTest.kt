package com.shilapi.xcertplay.vehicle.adapters.pioneer.wireless

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class HotspotPrewarmAuthorizationTest {
    @get:Rule val folder=TemporaryFolder()
    private val token="a".repeat(32)
    private fun go()=File(folder.root,"request.go")

    @Test fun cleanupCannotStopAnApWhoseCreationWasNeverAcquired() {
        assertFalse(HotspotPrewarmAuthorization.mayStopOwnedAp(false,false))
        assertFalse(HotspotPrewarmAuthorization.mayStopOwnedAp(true,true))
        assertTrue(HotspotPrewarmAuthorization.mayStopOwnedAp(true,false))
        assertTrue(HotspotPrewarmAuthorization.mayStopOwnedAp(null,false))
    }

    @Test fun initializationWaitsForThisAttemptsAuthorization() {
        val go=go()
        var time=0L
        var pauses=0
        HotspotPrewarmAuthorization.await(go,token,{}, { time }) {
            pauses++;time+=25
            if(pauses==3) go.writeText(token)
        }
        assertEquals(3,pauses)
    }
    @Test fun cancellationWinsEvenWhenAuthorizationAlreadyExists() {
        val go=go().apply { writeText(token) }
        assertThrows(IllegalStateException::class.java) {
            HotspotPrewarmAuthorization.await(go,token,{ error("cancelled") },{ 0L },{ fail("must not wait") })
        }
    }
    @Test fun aDifferentAttemptsTokenCannotAuthorizeExecution() {
        val go=go().apply { writeText("b".repeat(32)) }
        assertThrows(IllegalStateException::class.java) {
            HotspotPrewarmAuthorization.await(go,token,{}, { 0L },{ fail("must not wait") })
        }
    }
    @Test fun lateAuthorizationCannotReviveAnExpiredRequest() {
        val go=go()
        var time=0L
        assertThrows(IllegalStateException::class.java) {
            HotspotPrewarmAuthorization.await(go,token,{}, { time }) {
                time=60_001;go.writeText(token)
            }
        }
    }
}

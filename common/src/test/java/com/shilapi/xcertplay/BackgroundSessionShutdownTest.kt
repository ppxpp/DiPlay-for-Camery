package com.shilapi.xcertplay

import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.CarPlayController
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], manifest = Config.NONE)
class BackgroundSessionShutdownTest {
    @After fun clear() = CarPlayBackgroundSession.clear()
    private fun store(controller: CarPlayController, owner: Any) {
        CarPlayBackgroundSession.store(controller, mock(AndroidMediaSink::class.java), 800, 480,
            owner, CarPlaySessionDisplay(800, 480, 0, false, false, 800, 480)) { it() }
    }
    @Test fun shutdownOwnerCanClearItsRetainedRestartRecord() {
        val owner = Any()
        val controller = mock(CarPlayController::class.java)
        store(controller, owner)
        CarPlayBackgroundSession.clear(controller, keepOwner = true)
        assertNull(CarPlayBackgroundSession.snapshot())
        assertTrue(CarPlayBackgroundSession.hasSession())
        CarPlayBackgroundSession.clear(controller, expectedOwner = owner)
        assertFalse(CarPlayBackgroundSession.hasSession())
        assertFalse(CarPlayBackgroundSession.isOwner(owner))
    }
    @Test fun lateShutdownCannotClearAReplacementController() {
        val owner = Any()
        val old = mock(CarPlayController::class.java)
        store(old, owner)
        CarPlayBackgroundSession.clear(old, keepOwner = true)
        val next = mock(CarPlayController::class.java)
        store(next, owner)
        CarPlayBackgroundSession.clear(old, expectedOwner = owner)
        assertSame(next, CarPlayBackgroundSession.snapshot()!!.controller)
        assertTrue(CarPlayBackgroundSession.hasSession())
    }
    @Test fun lateShutdownCannotClearAnotherOwnersRestartRecord() {
        val oldOwner = Any()
        val old = mock(CarPlayController::class.java)
        store(old, oldOwner)
        val owner = Any()
        val next = mock(CarPlayController::class.java)
        store(next, owner)
        CarPlayBackgroundSession.clear(next, keepOwner = true)
        CarPlayBackgroundSession.clear(old, expectedOwner = oldOwner)
        assertTrue(CarPlayBackgroundSession.hasSession())
        assertTrue(CarPlayBackgroundSession.isOwner(owner))
    }
}

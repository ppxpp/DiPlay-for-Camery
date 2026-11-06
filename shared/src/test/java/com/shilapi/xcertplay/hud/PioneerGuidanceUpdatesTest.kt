package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.hud.PioneerGuidanceUpdates.Guidance
import org.junit.Assert.*
import org.junit.Test

class PioneerGuidanceUpdatesTest {
    @Test fun completedTurnClearsBeforeTheNewInstructionSettles() {
        val updates = PioneerGuidanceUpdates()
        val old = Guidance(1, "LEFT", 5, "Old road")
        val next = Guidance(2, "RIGHT", 300, "Next road")
        assertFalse(updates.shouldSend(old, next, 1_000_000_000))
        assertTrue(updates.shouldClear(old, next))
        // Clearing output must preserve confirmation that began on the first interleaved frame.
        assertFalse(updates.shouldSend(null, next.copy(distance = 290), 1_499_999_999))
        assertTrue(updates.shouldSend(null, next.copy(distance = 280), 1_500_000_000))
    }
    @Test fun sameDirectionNextTurnClearsButAliasesAndCorrectionsDoNot() {
        val updates = PioneerGuidanceUpdates()
        val old = Guidance(1, "LEFT", 20, "Road")
        assertTrue(updates.shouldClear(old, old.copy(maneuverIndex = 2, distance = 300)))
        assertFalse(updates.shouldClear(old, old.copy(maneuverIndex = 2, road = "Corrected")))
        assertFalse(updates.shouldClear(old, old.copy(distance = 10)))
        assertFalse(updates.shouldClear(null, old))
    }
    @Test fun newArrowDuringConfirmationRestartsDwellAndResetDiscardsPending() {
        val updates = PioneerGuidanceUpdates()
        val next = Guidance(2, "LEFT", 300, "Road")
        assertFalse(updates.shouldSend(null, next, 0))
        assertFalse(updates.shouldSend(null, next.copy(direction = "RIGHT"), 400_000_000))
        assertFalse(updates.shouldSend(null, next.copy(direction = "RIGHT"), 500_000_000))
        assertTrue(updates.shouldSend(null, next.copy(direction = "RIGHT"), 900_000_000))
        updates.reset()
        assertFalse(updates.shouldSend(null, next.copy(direction = "RIGHT"), 900_000_001))
    }
    @Test fun distanceRefreshIsCoalescedWithoutLosingTheNewestValue() {
        val updates = PioneerGuidanceUpdates()
        val old = Guidance(1, "LEFT", 100, "Road")
        updates.attempted(0)
        assertFalse(updates.shouldSend(old, old.copy(distance = 90), 499_999_999))
        assertTrue(updates.shouldSend(old, old.copy(distance = 80), 500_000_000))
        assertFalse(updates.shouldSend(old, old, 900_000_000))
    }
}

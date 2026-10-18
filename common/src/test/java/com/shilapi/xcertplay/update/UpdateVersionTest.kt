package com.shilapi.xcertplay.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateVersionTest {
    @Test
    fun higherVersionCodeIsAnUpdate() {
        assertTrue(UpdateVersion.isNewer(100L, 99L))
    }

    @Test
    fun sameVersionCodeIsNotAnUpdate() {
        assertFalse(UpdateVersion.isNewer(99L, 99L))
    }

    @Test
    fun lowerVersionCodeIsIgnored() {
        assertFalse(UpdateVersion.isNewer(98L, 99L))
    }

    @Test
    fun comparisonUsesTheNumericBuildNumber() {
        assertTrue(UpdateVersion.isNewer(1_000_000L, 999_999L))
        assertFalse(UpdateVersion.isNewer(1_000_000L, 1_000_001L))
    }
}

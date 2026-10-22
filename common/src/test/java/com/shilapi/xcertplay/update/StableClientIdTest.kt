package com.shilapi.xcertplay.update

import android.content.Context
import android.provider.Settings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class StableClientIdTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Test fun theIdIsStableAndTenCharacters() {
        setAndroidId("device-a")

        val first = StableClientId.get(context)
        val second = StableClientId.get(context)

        assertEquals(first, second)
        assertEquals(10, first?.length)
        assertTrue(first!!.matches(Regex("[A-Za-z0-9_-]{10}")))
    }

    @Test fun differentDevicesGetDifferentIds() {
        setAndroidId("device-a")
        val first = StableClientId.get(context)

        setAndroidId("device-b")
        val second = StableClientId.get(context)

        assertNotEquals(first, second)
    }

    @Test fun aMissingAndroidIdUsesAStableFallback() {
        setAndroidId("")

        val first = StableClientId.get(context)
        val second = StableClientId.get(context)

        assertTrue(first != null)
        assertEquals(10, first?.length)
        assertEquals(first, second)
    }

    private fun setAndroidId(value: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, value)
    }
}

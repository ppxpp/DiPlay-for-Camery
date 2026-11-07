package com.shilapi.xcertplay.update

import android.content.Context
import android.content.ContextWrapper
import android.provider.Settings
import java.io.File
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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
    private val root = File(context.cacheDir, "client-id-test-${UUID.randomUUID()}")

    @After fun cleanup() { root.deleteRecursively() }

    private fun installation(name: String): Context = object : ContextWrapper(context) {
        override fun getNoBackupFilesDir(): File = File(root, name).apply { mkdirs() }
    }

    @Test fun theIdIsStableAndTenCharacters() {
        setAndroidId("device-a")
        val app = installation("a")

        val first = StableClientId.get(app)
        val second = StableClientId.get(installation("a"))

        assertEquals(first, second)
        assertEquals(10, first?.length)
        assertTrue(first!!.matches(Regex("[A-Za-z0-9_-]{10}")))
    }

    @Test fun clonedAndroidIdsStillGetDifferentClientIds() {
        setAndroidId("cloned-head-unit-id")
        val first = StableClientId.get(installation("a"))
        val second = StableClientId.get(installation("b"))

        assertTrue(first != null && second != null)
        assertNotEquals(first, second)
    }

    @Test fun anAndroidIdChangeDoesNotRotateTheInstallationId() {
        val app = installation("a")
        setAndroidId("device-a")
        val first = StableClientId.get(app)
        setAndroidId("device-b")
        val second = StableClientId.get(app)

        assertEquals(first, second)
    }

    @Test fun aMissingAndroidIdUsesAStableFallback() {
        setAndroidId("")
        val app = installation("a")

        val first = StableClientId.get(app)
        val second = StableClientId.get(app)

        assertTrue(first != null)
        assertEquals(10, first?.length)
        assertEquals(first, second)
    }

    @Test fun legacyFallbackIsNotCopiedIntoTheNewInstallationId() {
        context.getSharedPreferences("diplay_update_client_v1", Context.MODE_PRIVATE)
            .edit().putString("fallback_id", "SharedOld1").commit()
        val first = StableClientId.get(installation("a"))
        val second = StableClientId.get(installation("b"))

        assertNotEquals("SharedOld1", first)
        assertNotEquals(first, second)
    }

    @Test fun concurrentFirstUseReturnsOnlyThePersistedId() {
        val app = installation("a")
        val executor = Executors.newFixedThreadPool(4)
        try {
            val ids = executor.invokeAll(List(12) { Callable { StableClientId.get(app) } })
                .map { it.get() }.toSet()
            assertEquals(setOf(StableClientId.get(installation("a"))), ids)
            assertTrue(null !in ids)
        } finally { executor.shutdownNow() }
    }

    @Test fun clearingInstallationStorageCreatesANewId() {
        val app = installation("a")
        val first = StableClientId.get(app)
        app.noBackupFilesDir.deleteRecursively()
        val second = StableClientId.get(installation("a"))
        assertTrue(first != null && second != null)
        assertNotEquals(first, second)
    }

    @Test fun unavailableStorageDoesNotReturnAnEphemeralOrAndroidIdBasedId() {
        val blocked = File(root, "blocked").apply { parentFile!!.mkdirs(); writeText("not a directory") }
        val app = object : ContextWrapper(context) {
            override fun getNoBackupFilesDir(): File = blocked
        }
        assertNull(StableClientId.get(app))
        assertNull(StableClientId.get(app))
    }

    private fun setAndroidId(value: String) {
        Settings.Secure.putString(context.contentResolver, Settings.Secure.ANDROID_ID, value)
    }
}

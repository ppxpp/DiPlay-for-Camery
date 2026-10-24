package com.shilapi.xcertplay.network

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.shilapi.xcertplay.pioneer.PioneerWiredBackend
import com.shilapi.xcertplay.pioneer.PioneerWiredSession
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.net.Inet6Address
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class NativeNcmLeaseTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()
    private val root: File get() = File(context.filesDir, "native-ncm")
    @Before fun prepare() { root.deleteRecursively(); root.mkdirs(); context.getSharedPreferences("pioneer-adapter", 0).edit().clear().commit() }
    @After fun cleanup() { root.deleteRecursively() }

    @Test fun prewarmFenceSurvivesAppProcessRestartUntilRootCompletion() {
        val result = File(root, "prewarm-00000000-0000-0000-0000-000000000000.json")
        for (state in listOf("LOADING", "UNCERTAIN")) {
            result.writeText(JSONObject().put("state", state).put("boot", "boot-a").toString())
            assertThrows(java.io.IOException::class.java) { NativeNcmLease.requirePrewarmFinished(context, "boot-a") }
        }
        for (state in listOf("READY", "FAILED")) {
            result.writeText(JSONObject().put("state", state).put("boot", "boot-a").toString())
            NativeNcmLease.requirePrewarmFinished(context, "boot-a")
        }
        result.writeText(JSONObject().put("state", "LOADING").put("boot", "boot-a").toString())
        NativeNcmLease.requirePrewarmFinished(context, "boot-b")
    }

    @Test fun lateDispatchCannotLoadAfterExpiryButRunningLoaderIsNeverAssumedFinished() {
        val result = File(root, "prewarm-00000000-0000-0000-0000-000000000000.json")
        result.writeText(JSONObject().put("state", "DISPATCH_PENDING").put("boot", "boot-a")
            .put("deadline", Long.MAX_VALUE).toString())
        assertThrows(java.io.IOException::class.java) { NativeNcmLease.requirePrewarmFinished(context, "boot-a") }
        result.writeText(JSONObject().put("state", "DISPATCH_PENDING").put("boot", "boot-a")
            .put("deadline", 0).toString())
        NativeNcmLease.requirePrewarmFinished(context, "boot-a")
        result.writeText(JSONObject().put("state", "LOADING").put("boot", "boot-a").put("deadline", 0).toString())
        assertThrows(java.io.IOException::class.java) { NativeNcmLease.requirePrewarmFinished(context, "boot-a") }
    }

    @Test fun defaultIsExistingAndFailureNeverChangesManualChoice() {
        assertFalse(PioneerWiredBackend.nativeEnabled(context))
        PioneerWiredBackend.saveNativeEnabled(context, true)
        val dir = journal("READY")
        assertTrue(runCatching { NativeNcmLease.requireReleased(context) }.isFailure)
        assertTrue(PioneerWiredBackend.nativeEnabled(context))
        assertTrue(File(dir, "stop").exists())
        PioneerWiredBackend.saveNativeEnabled(context, false)
        assertTrue(runCatching { NativeNcmLease.requireReleased(context) }.isFailure)
        assertFalse(PioneerWiredBackend.nativeEnabled(context))
    }

    @Test fun failedLaunchWithoutWorkerDoesNotPoisonBothBackends() {
        val dir = File(root, "deadlaunch").apply { mkdir() }
        NativeNcmLease.requireReleased(context)
        assertTrue(File(dir, "stop").exists())
    }

    @Test fun terminalStatusStillRequiresPhysicalWorkerLockRelease() {
        journal("CLOSED")
        RandomAccessFile(File(root, "owner.lock"), "rw").use { file ->
            file.channel.lock().use {
                assertTrue(runCatching { NativeNcmLease.requireReleased(context) }.isFailure)
            }
        }
        NativeNcmLease.requireReleased(context)
    }

    @Test fun incompleteSameBootCleanupBlocksButPreviousBootDoesNot() {
        val dir = journal("RELEASE_FAILED")
        assertTrue(runCatching { NativeNcmLease.requireReleased(context) }.isFailure)
        File(dir, "request.json").writeText(JSONObject().put("boot", "a-different-boot").toString())
        assertTrue(runCatching { NativeNcmLease.requireReleased(context, currentBoot = "unknown") }.isFailure)
        NativeNcmLease.requireReleased(context, currentBoot = "current-boot")
    }

    @Test fun terminalStatusCannotBypassUnresolvedFirewallJournal() {
        val dir = journal("FAILED")
        val file = File(dir, "firewall-journal.json")
        file.writeText(JSONObject().put("state", "UNCERTAIN").put("pending", "I0").put("unresolved", true).toString())
        assertTrue(runCatching { NativeNcmLease.requireReleased(context) }.isFailure)
        file.writeText(JSONObject().put("state", "RELEASED").put("pending", "none").put("unresolved", false).toString())
        NativeNcmLease.requireReleased(context)
    }

    @Test fun peerGetsPhysicalInterfaceScopeAndRejectsAnotherInterface() {
        val local = Inet6Address.getByAddress(null, InetAddress.getByName("fe80::2").address, 9)
        val binding = NativeNcmBinding(local, 9)
        assertEquals(9, binding.peer(InetAddress.getByName("fe80::1")).scopeId)
        assertTrue(runCatching { binding.peer(InetAddress.getByName("127.0.0.2")) }.isFailure)
        assertTrue(runCatching { binding.peer(InetAddress.getByName("2001:db8::1")) }.isFailure)
        assertTrue(runCatching { binding.peer(Inet6Address.getByAddress(null, local.address, 10)) }.isFailure)
    }

    @Test fun repeatedCloseCannotReleaseTheVehicleGuardUntilCleanupIsConfirmed() {
        val directory = journal("RELEASE_FAILED")
        fun publish(state: String) {
            File(directory, "status.json").writeText(JSONObject().put("token", "test-token")
                .put("state", state).toString())
        }
        publish("RELEASE_FAILED")
        val constructor = NativeNcmLease::class.java.declaredConstructors.single { it.parameterCount == 6 }
            .apply { isAccessible = true }
        val lease = constructor.newInstance(context, directory, "test-token", { true },
            { _: String -> Unit }, { _: Throwable -> Unit }) as NativeNcmLease
        PioneerWiredSession.begin(PioneerWiredSession.Backend.NATIVE_NCM, true).use { owner ->
            repeat(2) {
                val result = runCatching { lease.close(); owner.close() }
                assertTrue(result.exceptionOrNull() is java.io.IOException)
                assertTrue(PioneerWiredSession.active)
            }
            publish("CLOSED")
            lease.close()
            owner.close()
            assertFalse(PioneerWiredSession.active)
        }
    }

    private fun journal(state: String): File = File(root, "lease").apply {
        mkdir()
        File(this, "status.json").writeText(JSONObject().put("state", state).toString())
    }
}

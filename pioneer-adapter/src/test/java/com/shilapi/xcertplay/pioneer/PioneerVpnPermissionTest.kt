package com.shilapi.xcertplay.pioneer

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.nio.file.Files

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class PioneerVpnPermissionTest {
    @Test fun alreadyPreparedSkipsRootAndNoConsentWindowIsInvolved() {
        assertTrue(PioneerVpnPermissionGrant.authorize({ true }, { true }) { error("must skip root") })
    }

    @Test fun rootSuccessMustBeVerifiedByAppPrepare() {
        var prepared = false
        var calls = 0
        assertTrue(PioneerVpnPermissionGrant.authorize({ prepared }, { true }) { calls++; prepared = true })
        assertEquals(1, calls)
        assertFalse(PioneerVpnPermissionGrant.authorize({ false }, { true }) { calls++ })
        assertEquals(2, calls)
    }

    @Test fun cancellationBeforeGrantAndLateResultNeverAuthorize() {
        assertThrows(IllegalStateException::class.java) {
            PioneerVpnPermissionGrant.authorize({ true }, { false }) { error("must not grant") }
        }
        var current = true
        assertThrows(IllegalStateException::class.java) {
            PioneerVpnPermissionGrant.authorize({ false }, { current }) { current = false }
        }
    }

    @Test fun rootFailureIsPropagatedWithoutRetryOrBackendChange() {
        var calls = 0
        assertThrows(IOException::class.java) {
            PioneerVpnPermissionGrant.authorize({ false }, { true }) { calls++; throw IOException("denied") }
        }
        assertEquals(1, calls)
    }

    @Test fun concurrentAuthorizationRequestsShareSuccessfulGrant() {
        val prepared = java.util.concurrent.atomic.AtomicBoolean()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val first = pool.submit<Boolean> {
                PioneerVpnPermissionGrant.authorize(prepared::get, { true }) {
                    calls.incrementAndGet(); entered.countDown()
                    check(release.await(3, java.util.concurrent.TimeUnit.SECONDS)); prepared.set(true)
                }
            }
            assertTrue(entered.await(3, java.util.concurrent.TimeUnit.SECONDS))
            val second = pool.submit<Boolean> {
                PioneerVpnPermissionGrant.authorize(prepared::get, { true }) { calls.incrementAndGet() }
            }
            release.countDown()
            assertTrue(first.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(second.get(3, java.util.concurrent.TimeUnit.SECONDS))
            assertEquals(1, calls.get())
        } finally { release.countDown(); pool.shutdownNow() }
    }

    private fun request() = JSONObject().put("token", "token").put("package", "com.test.diplay")
        .put("uid", 10123).put("action", "GRANT").put("boot", "boot").put("deadline", 6000)

    private fun result() = JSONObject().put("token", "token").put("package", "com.test.diplay")
        .put("targetUid", 10123).put("helperUid", 0).put("action", "GRANT").put("code", "COMPLETED")

    @Test fun resultsAreBoundToTokenPackageUidRootAndAction() {
        PioneerVpnPermissionGrant.requireResult(result(), request())
        for ((key, value) in listOf("token" to "other", "package" to "com.other", "targetUid" to 10124,
            "helperUid" to 2000, "action" to "INSPECT")) {
            assertThrows(IllegalStateException::class.java) {
                PioneerVpnPermissionGrant.requireResult(result().put(key, value), request())
            }
        }
    }

    @Test fun unconfirmedPriorMutationFencesNewGrantEvenAfterDeadline() {
        val root = Files.createTempDirectory("vpn-fence").toFile()
        try {
            val dir = File(root, "lease").apply { mkdir() }
            File(dir, "request.json").writeText(request().toString())
            assertThrows(IllegalStateException::class.java) { PioneerVpnPermissionGrant.requirePreviousFinished(root, "boot", 5000) }
            PioneerVpnPermissionGrant.requirePreviousFinished(root, "boot", 7000) // No mutation, expired task cannot start.
            File(dir, "mutation").writeText("token")
            assertThrows(IllegalStateException::class.java) { PioneerVpnPermissionGrant.requirePreviousFinished(root, "boot", 7000) }
            File(dir, "result.json").writeText(result().put("unresolvedChild", true).toString())
            assertThrows(IllegalStateException::class.java) { PioneerVpnPermissionGrant.requirePreviousFinished(root, "boot", 7000) }
            File(dir, "result.json").writeText(result().toString())
            PioneerVpnPermissionGrant.requirePreviousFinished(root, "boot", 7000)
            PioneerVpnPermissionGrant.requirePreviousFinished(root, "next-boot", 0)
        } finally { root.deleteRecursively() }
    }

    @Test fun completedTaskRetentionPreservesUnknownMutationsAndChildren() {
        val root = Files.createTempDirectory("vpn-retention").toFile()
        try {
            repeat(20) { index ->
                File(root, "done-$index").apply {
                    mkdir()
                    File(this, "request.json").writeText(request().toString())
                    File(this, "result.json").writeText(result().toString())
                }
            }
            val unknown = File(root, "unknown").apply {
                mkdir(); File(this, "mutation").writeText("token")
                File(this, "request.json").writeText(request().toString())
            }
            val child = File(root, "child").apply {
                mkdir(); File(this, "request.json").writeText(request().toString())
                File(this, "result.json").writeText(result().put("unresolvedChild", true).toString())
            }
            val failed = File(root, "failed").apply {
                mkdir(); File(this, "mutation").writeText("token")
                File(this, "request.json").writeText(request().toString())
                File(this, "result.json").writeText(result().put("code", "ERROR").toString())
            }
            val safeFailure = File(root, "safe-failure").apply {
                mkdir(); File(this, "mutation").writeText("token")
                File(this, "request.json").writeText(request().toString())
                File(this, "result.json").writeText(result().put("code", "ERROR").put("childrenFinished", true).toString())
                setLastModified(1)
            }
            PioneerVpnPermissionGrant.cleanupCompleted(root, keep = 3)
            assertEquals(3, root.listFiles()!!.count { it.name.startsWith("done-") })
            assertTrue(unknown.exists()); assertTrue(child.exists()); assertTrue(failed.exists())
            assertFalse(safeFailure.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun helperOnlyAllowsFixedOperationsAndValidTaskDirectories() {
        listOf("INSPECT", "GRANT", "REVOKE").forEach { PioneerVpnPermissionHelper.requireAction(it) }
        for (action in listOf("SHELL", "GRANT;reboot", ""))
            assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requireAction(action) }
        val token = "01234567-0123-0123-0123-0123456789ab"
        PioneerVpnPermissionHelper.requireDirectory("/data/user/10/com.test.diplay/files/vpn-permission/$token")
        for (path in listOf("/sdcard/$token", "/data/user/0/com.test/files/vpn-permission/../$token", "/data/data/com.test/files/vpn-permission/not-a-token"))
            assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requireDirectory(path) }
    }

    @Test fun helperChecksPackageApkClasspathAndTaskDeadline() {
        val files = File("/data/user/0/com.test/files")
        PioneerVpnPermissionHelper.requirePackage(10123, 10123, "/apk", "/apk", "/apk", files, files)
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requirePackage(10123, 10124, "/apk", "/apk", "/apk", files, files) }
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requirePackage(10123, 10123, "/apk", "/other", "/apk", files, files) }
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requirePackage(10123, 10123, "/apk", "/apk", "/other", files, files) }
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requirePackage(10123, 10123, "/apk", "/apk", "/apk", files, File("/data/other")) }
        val request = JSONObject().put("requestedAtMs", 1000).put("deadline", 6000)
        PioneerVpnPermissionHelper.requireLive(request, 2000)
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requireLive(request, 6000) }
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requireLive(request, 999) }
        PioneerVpnPermissionHelper.requireLive(request.put("deadline",16000), 9000)
        assertThrows(IOException::class.java) { PioneerVpnPermissionHelper.requireLive(request.put("deadline", 17000), 2000) }
    }

    @Test fun appopsTargetsApplicationUserAndRequiresExactAllowedOperation() {
        assertArrayEquals(arrayOf("/system/bin/appops", "set", "--user", "10", "com.test.diplay", "ACTIVATE_VPN", "allow"),
            PioneerVpnPermissionHelper.arguments("set", "com.test.diplay", 10))
        assertThrows(IllegalArgumentException::class.java) { PioneerVpnPermissionHelper.arguments("set", "com.test;reboot", 0) }
        assertTrue(PioneerVpnPermissionHelper.isAllowed("ACTIVATE_VPN: allow; time=+1s"))
        assertTrue(PioneerVpnPermissionHelper.isAllowed("android:activate_vpn: allow"))
        for (output in listOf("No operations.", "ACTIVATE_VPN: ignore", "ACTIVATE_PLATFORM_VPN: allow", "error allow", "ACTIVATE_VPN: allowed"))
            assertFalse(PioneerVpnPermissionHelper.isAllowed(output))
    }
}

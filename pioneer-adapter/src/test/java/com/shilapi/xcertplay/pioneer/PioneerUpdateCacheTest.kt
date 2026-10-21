package com.shilapi.xcertplay.pioneer

import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class PioneerUpdateCacheTest {
    @get:Rule val files = TemporaryFolder()
    private val root get() = files.root
    private fun apk(name: String, protected: Boolean = false): File = File(root, "$name.apk").apply {
        writeText("APK payload $name")
        if (protected) File(path + ".installing").writeText("")
    }

    @Test fun removesRecentUnprotectedUploadsAndOrphanMarkersWithoutWaitingADay() {
        val a = apk("incomplete")
        File(root, "missing.apk.installing").writeText("")
        val other = File(root, "unrelated.log").apply { writeText("keep") }
        val bytes = a.length()
        val result = PioneerUpdateCache.prune(root)
        assertEquals(bytes, result.reclaimedBytes)
        assertEquals(0L, result.remainingBytes)
        assertEquals(0, result.failedDeletes)
        assertFalse(a.exists())
        assertFalse(File(root, "missing.apk.installing").exists())
        assertTrue(other.exists())
    }

    @Test fun keepsUnconfirmedInstallerAndAllowsOnlyConfirmedInstalledCopyToBeReclaimed() {
        val pending = apk("pending", true)
        val completed = apk("completed", true)
        val result = PioneerUpdateCache.prune(root) { it == completed }
        assertTrue(pending.exists())
        assertTrue(File(pending.path + ".installing").exists())
        assertFalse(completed.exists())
        assertFalse(File(completed.path + ".installing").exists())
        assertEquals(1, result.protectedApks)
    }

    @Test fun unavailableInstalledPackageInformationNeverReleasesProtection() {
        val pending = apk("pending", true)
        PioneerUpdateCache.prune(root) { throw IOException("PackageManager unavailable") }
        assertTrue(pending.exists())
    }

    @Test fun terminalResultOnlyRemovesItsOwnApkAndIsIdempotent() {
        val previous = apk("previous", true)
        val next = apk("next", true)
        assertTrue(PioneerUpdateCache.finish(root, previous))
        assertTrue(PioneerUpdateCache.finish(root, previous))
        assertFalse(File(previous.path + ".installing").exists())
        assertTrue(next.exists())
        assertTrue(File(next.path + ".installing").exists())
        val outside = files.newFolder("outside").resolve("bad.apk").apply { writeText("outside") }
        assertThrows(IllegalArgumentException::class.java) { PioneerUpdateCache.finish(root, outside) }
        assertTrue(outside.exists())
    }

    @Test fun uploadCountIsBoundedEvenWhenAllInputsAreProtected() {
        repeat(PioneerUpdateCache.MAX_APKS) { apk("pending$it", true) }
        PioneerUpdateCache.prune(root)
        assertThrows(IOException::class.java) { PioneerUpdateCache.requireCapacity(root, 1, Long.MAX_VALUE) }
        assertEquals(PioneerUpdateCache.MAX_APKS, root.listFiles()!!.count { it.extension == "apk" })
    }

    @Test fun byteLimitAndFreeSpaceReserveAreCheckedBeforeReceiving() {
        val pending = apk("large", true)
        RandomAccessFile(pending, "rw").use { it.setLength(PioneerUpdateCache.MAX_BYTES - 10) }
        PioneerUpdateCache.requireCapacity(root, 10, Long.MAX_VALUE)
        assertThrows(IOException::class.java) { PioneerUpdateCache.requireCapacity(root, 11, Long.MAX_VALUE) }
        assertThrows(IOException::class.java) { PioneerUpdateCache.requireCapacity(root, 10, PioneerUpdateCache.FREE_RESERVE_BYTES + 9) }
        PioneerUpdateCache.requireCapacity(root, 10, PioneerUpdateCache.FREE_RESERVE_BYTES + 10)
    }

    @Test fun oldAbandonedMarkerExpiresButClockRollbackDoesNotExpireIt() {
        val pending = apk("old", true)
        val marker = File(pending.path + ".installing")
        val now = System.currentTimeMillis()
        assertTrue(marker.setLastModified(now + TimeUnit.DAYS.toMillis(1)))
        assertTrue(PioneerUpdateCache.isProtected(pending, now))
        assertTrue(marker.setLastModified(now - TimeUnit.HOURS.toMillis(25)))
        PioneerUpdateCache.prune(root)
        assertFalse(pending.exists())
    }

    @Test fun samePreviouslyInstalledBytesAreNotProofOfThisInstallation() {
        val candidate = apk("identical", true)
        val handoff = File(candidate.path + ".installing").lastModified()
        val digest = MessageDigest.getInstance("SHA-256").digest(candidate.readBytes())
        assertFalse(PioneerDebugUpdater.installedCopy(candidate, handoff - 1, digest))
        assertTrue(PioneerDebugUpdater.installedCopy(candidate, handoff + 1, digest))
        assertFalse(PioneerDebugUpdater.installedCopy(candidate, handoff + 1, ByteArray(32)))
    }

    @Test fun repeatedCompletedUploadsDoNotAccumulate() {
        repeat(100) { index ->
            PioneerUpdateCache.prune(root)
            PioneerUpdateCache.requireCapacity(root, 100, Long.MAX_VALUE)
            val candidate = apk("build$index", true)
            if (index % 2 == 0) PioneerUpdateCache.finish(root, candidate)
            else PioneerUpdateCache.prune(root) { true } // Process replaced: installed copy verified.
        }
        assertTrue(root.listFiles()!!.isEmpty())
    }
}

package com.shilapi.xcertplay.network

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeLeaseRetentionTest {
    @Test fun keepsOnlyTheNewestCompletedLeaseDirectories() {
        val root = Files.createTempDirectory("diplay-native-leases").toFile()
        try {
            val now = System.currentTimeMillis()
            repeat(25) { index ->
                File(root, "lease-%02d".format(index)).apply {
                    mkdirs()
                    File(this, "status.json").apply {
                        writeText("{}")
                        setLastModified(now - (24 - index) * 1_000L)
                    }
                }
            }

            NativeLeaseRetention.prune(root, now = now, isSafelyCompleted = { true })

            val retained = root.listFiles()!!.map { it.name }.toSet()
            assertEquals(20, retained.size)
            assertTrue(retained.contains("lease-24"))
            assertTrue(retained.contains("lease-05"))
            assertTrue(!retained.contains("lease-00"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun retainsUnconfirmedLeasesAndExpiresOldCompletedOnes() {
        val root = Files.createTempDirectory("diplay-native-unconfirmed").toFile()
        try {
            val now = System.currentTimeMillis()
            val unsafe = File(root, "unsafe").apply { mkdirs() }
            val recent = File(root, "recent").apply { mkdirs() }
            val expired = File(root, "expired").apply { mkdirs() }
            File(unsafe, "status.json").apply { writeText("{}"); setLastModified(now - 30L * 24L * 60L * 60L * 1000L) }
            File(recent, "status.json").apply { writeText("{}"); setLastModified(now) }
            File(expired, "status.json").apply { writeText("{}"); setLastModified(now - 8L * 24L * 60L * 60L * 1000L) }

            NativeLeaseRetention.prune(root, now = now, isSafelyCompleted = { it.name != "unsafe" })

            assertTrue(unsafe.isDirectory)
            assertTrue(recent.isDirectory)
            assertTrue(!expired.exists())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun aFreshStopMarkerDoesNotKeepAnExpiredCompletedLease() {
        val root = Files.createTempDirectory("diplay-native-stop").toFile()
        try {
            val now = System.currentTimeMillis()
            val directory = File(root, "old").apply { mkdirs() }
            File(directory, "status.json").apply {
                writeText("{}")
                setLastModified(now - 8L * 24L * 60L * 60L * 1000L)
            }
            File(directory, "stop").writeText("old")

            NativeLeaseRetention.prune(root, now = now, isSafelyCompleted = { true })

            assertTrue(!directory.exists())
        } finally {
            root.deleteRecursively()
        }
    }
}

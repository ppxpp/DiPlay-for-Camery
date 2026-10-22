package com.shilapi.xcertplay.update

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateDownloadCacheTest {
    @get:Rule val files = TemporaryFolder()

    @Test fun processStartupRemovesHistoricalDownloadDirectoriesButKeepsCurrentOwner() {
        val root = files.newFolder("update")
        val current = File(root, "current").apply { mkdirs() }
        val old = File(root, "old").apply { mkdirs() }
        val older = File(root, "older").apply { mkdirs() }
        File(current, "current.apk").writeText("current")
        File(old, "old.apk").writeText("old")
        File(older, "older.apk").writeText("older")

        UpdateDownloadCache.removeHistoricalDirectories(root, keep = current)

        assertTrue(current.isDirectory)
        assertFalse(old.exists())
        assertFalse(older.exists())
    }

    @Test fun legacyExportDirectoryKeepsOnlyNewestApk() {
        val directory = files.newFolder("legacy")
        val oldest = File(directory, "old.apk").apply {
            writeText("old")
            setLastModified(1_000L)
        }
        val newest = File(directory, "new.apk").apply {
            writeText("new")
            setLastModified(2_000L)
        }
        val unrelated = File(directory, "notes.txt").apply { writeText("keep") }

        UpdateApkExport.pruneDirectoryHistory(directory)

        assertFalse(oldest.exists())
        assertTrue(newest.exists())
        assertTrue(unrelated.exists())
    }
}

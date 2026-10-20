package com.shilapi.xcertplay.vehicle.adapters.pioneer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PalDexRetentionTest {
    @Test fun removesOnlyOldPalDexFiles() {
        val directory = Files.createTempDirectory("diplay-pal-dex").toFile()
        try {
            val current = File(directory, "pal-current.dex").apply { writeText("current") }
            val old = File(directory, "pal-old.dex").apply { writeText("old") }
            val readOnlyOld = File(directory, "pal-readonly.dex").apply {
                writeText("old")
                setReadOnly()
            }
            File(directory, "other.dex").writeText("not-pal")
            File(directory, "pal-note.txt").writeText("not-dex")

            PalSteeringWheelInputAdapter.pruneOldPalDex(directory, current)

            assertTrue(current.isFile)
            assertFalse(old.exists())
            assertFalse(readOnlyOld.exists())
            assertTrue(File(directory, "other.dex").isFile)
            assertTrue(File(directory, "pal-note.txt").isFile)
        } finally {
            directory.deleteRecursively()
        }
    }
}

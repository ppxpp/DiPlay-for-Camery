package com.shilapi.xcertplay.vehicle.adapters.pioneer

import java.io.File
import java.nio.file.Files
import org.junit.Assert.*
import org.junit.Test

class PalSdkCacheMigrationTest {
    @Test fun removesHashedDexAndLockAndTemporaryFileButPreservesOtherFiles() {
        val root = Files.createTempDirectory("diplay-pal-migration").toFile()
        try {
            val directory = File(root, "vehicle-pal-sdk").apply { mkdir() }
            val dex = File(directory, "pal-${"a".repeat(64)}.dex").apply { writeText("old"); setReadOnly() }
            val lock = File(directory, dex.name + ".flock").apply { writeText("lock") }
            val temporary = File(directory, "pal.tmp").apply { writeText("partial") }
            val unrelated = listOf("other.dex", "pal-note.txt", "pal-short.dex", "pal-${"b".repeat(64)}Xdex")
                .map { File(directory, it).apply { writeText("keep") } }
            val nested = File(directory, "pal-${"c".repeat(64)}.dex").apply { mkdir() }
            val child = File(nested, "keep").apply { writeText("keep") }

            PalSteeringWheelInputAdapter.clearLegacySdkCacheDirectory(directory)

            assertFalse(dex.exists())
            assertFalse(lock.exists())
            assertFalse(temporary.exists())
            unrelated.forEach { assertEquals("keep", it.readText()) }
            assertEquals("keep", child.readText())
        } finally { root.deleteRecursively() }
    }

    @Test fun removesEmptyCacheDirectoryAndToleratesRepeatedMigration() {
        val root = Files.createTempDirectory("diplay-pal-migration").toFile()
        try {
            val directory = File(root, "vehicle-pal-opt").apply { mkdir() }
            File(directory, "pal-${"d".repeat(64)}.dex").writeText("old")
            PalSteeringWheelInputAdapter.clearLegacySdkCacheDirectory(directory)
            assertFalse(directory.exists())
            PalSteeringWheelInputAdapter.clearLegacySdkCacheDirectory(directory)
            assertFalse(directory.exists())
        } finally { root.deleteRecursively() }
    }

    @Test fun substitutedDirectoryDoesNotDeleteTargetArtifacts() {
        val root = Files.createTempDirectory("diplay-pal-migration").toFile()
        val external = Files.createTempDirectory("diplay-pal-external").toFile()
        val directory = File(root, "vehicle-pal-sdk")
        try {
            val target = File(external, "pal-${"e".repeat(64)}.dex").apply { writeText("keep") }
            Files.createSymbolicLink(directory.toPath(), external.toPath())
            PalSteeringWheelInputAdapter.clearLegacySdkCacheDirectory(directory)
            assertEquals("keep", target.readText())
            assertTrue(Files.isSymbolicLink(directory.toPath()))
        } finally {
            Files.deleteIfExists(directory.toPath())
            root.deleteRecursively()
            external.deleteRecursively()
        }
    }
}

package com.shilapi.xcertplay.airplay

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AudioCaptureRetentionTest {
    @Test fun keepsNewestCaptureFilesWithinTheConfiguredCaps() {
        val directory = Files.createTempDirectory("diplay-audio-retention").toFile()
        try {
            val now = System.currentTimeMillis()
            repeat(6) { index ->
                File(directory, "audio-$index.xcap").apply {
                    writeBytes(ByteArray(1_000))
                    setLastModified(now - index * 1_000L)
                }
            }
            File(directory, "notes.txt").writeText("keep")

            AudioPacketCapture.prune(directory, maxFiles = 4, maxBytes = 10_000L, keep = emptySet())

            val retained = directory.listFiles()!!.map { it.name }.toSet()
            assertEquals(setOf("audio-0.xcap", "audio-1.xcap", "audio-2.xcap", "audio-3.xcap", "notes.txt"), retained)
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun neverDeletesAnActiveCaptureEvenWhenTheFileCapIsExceeded() {
        val directory = Files.createTempDirectory("diplay-audio-active").toFile()
        try {
            val active = File(directory, "audio-active.xcap").apply {
                writeBytes(ByteArray(1_000))
                setLastModified(System.currentTimeMillis() - 10_000L)
            }
            repeat(3) { index ->
                File(directory, "audio-$index.xcap").apply {
                    writeBytes(ByteArray(1_000))
                    setLastModified(System.currentTimeMillis() + index)
                }
            }

            AudioPacketCapture.prune(directory, maxFiles = 1, maxBytes = 10_000L, keep = setOf(active))

            assertTrue(active.isFile)
            assertEquals(1, directory.listFiles()!!.count { it.extension == "xcap" })
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test fun byteCapKeepsTheNewestFilesAndIgnoresNonCaptureFiles() {
        val directory = Files.createTempDirectory("diplay-audio-bytes").toFile()
        try {
            val now = System.currentTimeMillis()
            repeat(3) { index ->
                File(directory, "audio-$index.xcap").apply {
                    writeBytes(ByteArray(100))
                    setLastModified(now - index * 1_000L)
                }
            }
            File(directory, "keep.log").writeText("not a capture")

            AudioPacketCapture.prune(directory, maxFiles = 10, maxBytes = 250L, keep = emptySet())

            val retained = directory.listFiles()!!.map { it.name }.toSet()
            assertEquals(setOf("audio-0.xcap", "audio-1.xcap", "keep.log"), retained)
        } finally {
            directory.deleteRecursively()
        }
    }
}

package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile
import kotlin.random.Random

class WavPcm16Test {
    @Test
    fun roundTripsHeaderAndPcmData() {
        val file = File.createTempFile("car-mic-", ".wav").apply { deleteOnExit() }
        val pcm = Random(7).nextBytes(3_200)
        RandomAccessFile(file, "rw").use { output ->
            output.setLength(0L)
            output.write(ByteArray(WavPcm16.HEADER_BYTES))
            output.seek(WavPcm16.HEADER_BYTES.toLong())
            output.write(pcm)
            WavPcm16.writeHeader(output, 16_000, 1, pcm.size.toLong())
        }

        val info = WavPcm16.read(file)
        assertNotNull(info)
        checkNotNull(info)
        assertEquals(16_000, info.sampleRate)
        assertEquals(1, info.channels)
        assertEquals(WavPcm16.HEADER_BYTES.toLong(), info.dataOffset)
        assertEquals(pcm.size.toLong(), info.dataBytes)

        RandomAccessFile(file, "r").use { input ->
            input.seek(info.dataOffset)
            val actual = ByteArray(info.dataBytes.toInt())
            input.readFully(actual)
            assertArrayEquals(pcm, actual)
        }
    }

    @Test
    fun rejectsEmptyAndNonWavFiles() {
        val empty = File.createTempFile("car-mic-empty-", ".wav").apply { deleteOnExit() }
        assertNull(WavPcm16.read(empty))

        val junk = File.createTempFile("car-mic-junk-", ".wav").apply {
            deleteOnExit()
            writeText("this is not a wave file")
        }
        assertNull(WavPcm16.read(junk))
    }
}

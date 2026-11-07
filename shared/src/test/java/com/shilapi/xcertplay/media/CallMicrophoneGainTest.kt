package com.shilapi.xcertplay.media

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class CallMicrophoneGainTest {
    @Test fun doublesSignedLittleEndianSamplesAndPreservesSilence() {
        val pcm = samples(0, 1, -1, 12000, -12000)
        CallMicrophoneGain.apply(pcm)
        assertArrayEquals(samples(0, 2, -2, 24000, -24000), pcm)
    }

    @Test fun loudSamplesSaturateWithoutWrappingPolarity() {
        val pcm = samples(20000, -20000, 32767, -32768)
        CallMicrophoneGain.apply(pcm)
        assertArrayEquals(samples(32767, -32768, 32767, -32768), pcm)
    }

    private fun samples(vararg values: Int) = ByteArray(values.size * 2).also { pcm ->
        values.forEachIndexed { index, value ->
            pcm[index * 2] = value.toByte()
            pcm[index * 2 + 1] = (value shr 8).toByte()
        }
    }
}

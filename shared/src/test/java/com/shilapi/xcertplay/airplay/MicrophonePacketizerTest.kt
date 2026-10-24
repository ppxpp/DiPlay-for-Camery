package com.shilapi.xcertplay.airplay

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Crypto selection consults Android's native-loader/logging APIs even for software encryption.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [25], manifest = Config.NONE)
class MicrophonePacketizerTest {
    @Test
    fun packetUsesRtpHeaderAndInputKeyAad() {
        val key = ByteArray(32) { index -> (index + 1).toByte() }
        val counters = MicrophoneCounters()
        val body = byteArrayOf(0x28, 0x01, 0x02, 0x03)

        val packet = MicrophonePacketizer.sealPacket(key, 100, counters, body, 480)

        assertEquals(0x80, packet[0].toInt() and 0xff)
        assertEquals(100, packet[1].toInt() and 0xff)
        assertEquals(0, ((packet[2].toInt() and 0xff) shl 8) or (packet[3].toInt() and 0xff))
        assertEquals(
            0,
            ((packet[4].toInt() and 0xff) shl 24) or
                ((packet[5].toInt() and 0xff) shl 16) or
                ((packet[6].toInt() and 0xff) shl 8) or
                (packet[7].toInt() and 0xff),
        )

        val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
        val nonce = ByteArray(12)
        packet.copyInto(nonce, 4, sealedEnd, packet.size)
        val opened = AirPlayCrypto.chachaOpen(
            key,
            nonce,
            packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
            packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN),
        )

        assertArrayEquals(body, opened)
        assertEquals(1, counters.sequence)
        assertEquals(480, counters.timestamp)
        assertEquals(1L, counters.nonce)
    }

    @Test
    fun pcmIsConvertedToBigEndian() {
        assertArrayEquals(
            byteArrayOf(0x12, 0x34, 0x56, 0x78),
            MicrophonePacketizer.toWirePcm(
                byteArrayOf(0x34, 0x12, 0x78, 0x56),
            ),
        )
    }

    @Test
    fun opusTimestampsCountInTheClockTheIphoneChose() {
        // Siri asks for Opus 24 kHz (0x20000000) and calls for Opus 48 kHz (0x40000000); DiPlay still
        // captures 20 ms at 48 kHz, but each packet moves the RTP clock by 20 ms of the chosen rate.
        assertEquals(24_000, MicrophoneConfig.opusClockRate(0x20000000L))
        assertEquals(48_000, MicrophoneConfig.opusClockRate(0x40000000L))
        assertEquals(16_000, MicrophoneConfig.opusClockRate(0x10000000L))
        assertEquals(48_000, MicrophoneConfig.opusClockRate(0L))

        val siri = config(AudioCodecKind.OPUS, opusClockRate = 24_000)
        assertEquals(960, siri.samplesPerPacket)
        assertEquals(1920, siri.frameBytes)
        assertEquals(480, siri.rtpSamplesPerPacket)
        assertEquals(960, config(AudioCodecKind.OPUS).rtpSamplesPerPacket)
        // PCM keeps its own rate: 20 ms at 16 kHz.
        assertEquals(320, config(AudioCodecKind.LPCM, sampleRate = 16_000).rtpSamplesPerPacket)
    }

    @Test
    fun observedSiriPacketsUse480TicksWithoutChangingCapturedSamplesOrPayload() {
        assertPacketSequence(0x20000000L, 480)
    }

    @Test
    fun callPacketsAndFormatsWithoutOpusBitsKeepTheStandard960TickClock() {
        for (format in listOf(0x40000000L, 0L, 0x60000000L)) {
            assertPacketSequence(format, 960)
        }
    }

    @Test
    fun opus16KhzPacketsUse320Ticks() {
        assertPacketSequence(0x10000000L, 320)
    }

    private fun assertPacketSequence(format: Long, expectedStep: Int) {
        val stream = config(AudioCodecKind.OPUS, opusClockRate = MicrophoneConfig.opusClockRate(format))
        assertEquals(960, stream.samplesPerPacket)
        assertEquals(1920, stream.frameBytes)
        val key = ByteArray(32) { (it + 1).toByte() }
        val counters = MicrophoneCounters()
        // Synthetic encoded bytes with a one-frame, 20 ms CELT TOC; this test checks RTP sealing,
        // not Opus encoding. Each packet must preserve the supplied encoded payload exactly.
        repeat(3) { index ->
            val payload = byteArrayOf(0xf8.toByte(), index.toByte(), 0x31, 0x42)
            val packet = MicrophonePacketizer.sealPacket(key, stream.payloadType, counters, payload,
                stream.rtpSamplesPerPacket)
            val header = java.nio.ByteBuffer.wrap(packet).order(java.nio.ByteOrder.BIG_ENDIAN)
            assertEquals(index, header.getShort(2).toInt() and 0xffff)
            assertEquals(index * expectedStep, header.getInt(4))
            val sealedEnd = packet.size - MicrophonePacketizer.NONCE_LEN
            val nonce = ByteArray(12)
            packet.copyInto(nonce, 4, sealedEnd, packet.size)
            assertEquals(index.toLong(), java.nio.ByteBuffer.wrap(packet, sealedEnd, 8)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN).long)
            assertArrayEquals(payload, AirPlayCrypto.chachaOpen(key, nonce,
                packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, sealedEnd),
                packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN)))
        }
        assertEquals(3 * expectedStep, counters.timestamp)
        assertEquals(3, counters.sequence)
        assertEquals(3L, counters.nonce)
    }

    private fun config(codec: AudioCodecKind, sampleRate: Int = 48_000, opusClockRate: Int = 48_000) = MicrophoneConfig(
        audioType = "speechrecognition",
        sampleRate = sampleRate,
        channels = 1,
        payloadType = 100,
        frameMillis = 20,
        host = java.net.InetAddress.getLoopbackAddress(),
        port = 1,
        key = ByteArray(32),
        codec = codec,
        opusClockRate = opusClockRate,
    )
    @org.junit.Test fun shortenOnlyOversizedPcmWithoutChangingItsRate() {
        val phone = config(AudioCodecKind.LPCM).copy(sampleRate = 32_000, channels = 1, frameMillis = 20)
        org.junit.Assert.assertEquals(20, phone.withPcmPacketBudget(10, 1500, true).frameMillis)
        val wechat = phone.copy(sampleRate = 48_000)
        val short = wechat.withPcmPacketBudget(10, 1500, true)
        org.junit.Assert.assertEquals(48_000, short.sampleRate)
        org.junit.Assert.assertEquals(960, short.frameBytes)
        org.junit.Assert.assertEquals(480, short.rtpSamplesPerPacket)
        org.junit.Assert.assertTrue(short.frameBytes + 84 <= 1500)
        org.junit.Assert.assertEquals(20, wechat.withPcmPacketBudget(0, 1500, true).frameMillis)
    }

    @org.junit.Test fun packetBudgetAdaptsToSmallerInterfacesAndStereo() {
        val mono = config(AudioCodecKind.LPCM).copy(sampleRate = 32_000, channels = 1, frameMillis = 20)
        org.junit.Assert.assertEquals(10, mono.withPcmPacketBudget(10, 1280, true).frameMillis)
        val stereo = mono.copy(sampleRate = 48_000, channels = 2)
        org.junit.Assert.assertEquals(5, stereo.withPcmPacketBudget(10, 1280, true).frameMillis)
        org.junit.Assert.assertEquals(20, stereo.withPcmPacketBudget(10, 9000, true).frameMillis)
    }

    @org.junit.Test fun packetBudgetCountsAddressFamilyAndRejectsImpossibleFrames() {
        val pcm = config(AudioCodecKind.LPCM).copy(sampleRate = 48_000, channels = 1, frameMillis = 10)
        org.junit.Assert.assertEquals(10, pcm.withPcmPacketBudget(10, 1030, false).frameMillis)
        org.junit.Assert.assertEquals(5, pcm.withPcmPacketBudget(10, 1030, true).frameMillis)
        try { pcm.withPcmPacketBudget(10, 100, true); org.junit.Assert.fail() }
        catch (_: IllegalArgumentException) { }
        val opus = config(AudioCodecKind.OPUS)
        org.junit.Assert.assertSame(opus, opus.withPcmPacketBudget(10, 1280, true))
    }

    @Test fun exactPacketBoundaryFitsButOneByteLessSelectsASmallerFrame() {
        val pcm = config(AudioCodecKind.LPCM).copy(frameMillis = 10)
        assertEquals(10, pcm.withPcmPacketBudget(10, 1044, true).frameMillis)
        assertEquals(5, pcm.withPcmPacketBudget(10, 1043, true).frameMillis)
        val short = pcm.copy(frameMillis = 5)
        org.junit.Assert.assertSame(short, short.withPcmPacketBudget(10, 1280, true))
        for ((duration, mtu) in listOf(3 to 1280, 10 to 0, 10 to -1)) {
            try { pcm.withPcmPacketBudget(duration, mtu, true); org.junit.Assert.fail() }
            catch (_: IllegalArgumentException) { }
        }
    }
    @Test fun splitPcmPacketsPreserveAudioAndClockAcrossEncryption() {
        val original = config(AudioCodecKind.LPCM).copy(frameMillis = 20)
        val shortened = original.withPcmPacketBudget(10, 1280, true)
        val pcm = ByteArray(original.frameBytes) { (it * 37).toByte() }
        val expectedWire = MicrophonePacketizer.toWirePcm(pcm)
        val counters = MicrophoneCounters()
        val decoded = java.io.ByteArrayOutputStream()
        for (index in 0 until original.frameBytes / shortened.frameBytes) {
            val body = MicrophonePacketizer.toWirePcm(pcm.copyOfRange(index * shortened.frameBytes, (index + 1) * shortened.frameBytes))
            val packet = MicrophonePacketizer.sealPacket(original.key, original.payloadType, counters, body, shortened.rtpSamplesPerPacket)
            org.junit.Assert.assertTrue(packet.size + 48 <= 1280)
            assertEquals(index, java.nio.ByteBuffer.wrap(packet).getShort(2).toInt() and 65535)
            assertEquals(index * shortened.samplesPerPacket, java.nio.ByteBuffer.wrap(packet).getInt(4))
            val end = packet.size - MicrophonePacketizer.NONCE_LEN
            val nonce = ByteArray(12)
            packet.copyInto(nonce, 4, end, packet.size)
            val plain = AirPlayCrypto.chachaOpen(original.key, nonce,
                packet.copyOfRange(MicrophonePacketizer.RTP_HEADER_LEN, end), packet.copyOfRange(4, MicrophonePacketizer.RTP_HEADER_LEN))
            decoded.write(plain)
        }
        assertArrayEquals(expectedWire, decoded.toByteArray())
        assertEquals(original.samplesPerPacket, counters.timestamp)
        assertEquals(2, counters.sequence)
        assertEquals(2L, counters.nonce)
    }

}

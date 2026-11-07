package com.shilapi.xcertplay.media

/** About +6 dB for the Pioneer call mic, with saturation instead of signed PCM overflow. */
internal object CallMicrophoneGain {
    fun apply(pcm: ByteArray) {
        var offset = 0
        while (offset + 1 < pcm.size) {
            val sample = (pcm[offset].toInt() and 0xff) or (pcm[offset + 1].toInt() shl 8)
            val amplified = (sample * 2).coerceIn(-32768, 32767)
            pcm[offset] = amplified.toByte()
            pcm[offset + 1] = (amplified shr 8).toByte()
            offset += 2
        }
    }
}

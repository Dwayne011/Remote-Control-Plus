package com.remotecontrolplus.core

import android.media.MediaCodec
import android.media.MediaFormat

/**
 * Raw Opus packets from 16-bit mono PCM, using Android's built-in encoder
 * (MediaCodec "audio/opus", Android 10+). Feed it exactly one 20 ms frame at a
 * time; each call returns the packets produced so far (usually one).
 */
class OpusEncoder(private val sampleRate: Int, bitRate: Int = 24_000) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_OPUS)
    private val info = MediaCodec.BufferInfo()
    private var presentationUs = 0L

    init {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_OPUS, sampleRate, 1).apply {
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_PCM_ENCODING, android.media.AudioFormat.ENCODING_PCM_16BIT)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4096)
            setInteger(MediaFormat.KEY_COMPLEXITY, 5)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        Diagnostics.log("voice", "Opus encoder ready (${codec.name}) at $sampleRate Hz, $bitRate bps")
    }

    fun encode(pcm: ByteArray): List<ByteArray> {
        val inIndex = codec.dequeueInputBuffer(20_000)
        if (inIndex >= 0) {
            val buffer = codec.getInputBuffer(inIndex)!!
            buffer.clear()
            buffer.put(pcm)
            codec.queueInputBuffer(inIndex, 0, pcm.size, presentationUs, 0)
            presentationUs += pcm.size / 2 * 1_000_000L / sampleRate
        }
        val packets = mutableListOf<ByteArray>()
        while (true) {
            val outIndex = codec.dequeueOutputBuffer(info, 0)
            if (outIndex < 0) break
            val out = codec.getOutputBuffer(outIndex)!!
            // Codec-specific data (OpusHead etc.) is not audio; skip it.
            if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                val packet = ByteArray(info.size)
                out.position(info.offset)
                out.get(packet)
                packets += packet
            }
            codec.releaseOutputBuffer(outIndex, false)
        }
        return packets
    }

    fun release() {
        runCatching { codec.stop() }
        codec.release()
    }

}

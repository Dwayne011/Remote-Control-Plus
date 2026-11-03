package com.remotecontrolplus.core

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

/**
 * Captures mono 16-bit PCM from the phone microphone and hands it over in
 * fixed-size chunks until the calling coroutine is cancelled.
 *
 * The caller must already hold RECORD_AUDIO; the UI requests it before
 * starting a voice session.
 */
class VoiceRecorder(
    private val sampleRate: Int,
    /** Bytes per chunk handed to [onChunk]; the protocol decides the size. */
    private val chunkBytes: Int,
) {
    @SuppressLint("MissingPermission")
    suspend fun record(onChunk: suspend (ByteArray) -> Unit) = withContext(Dispatchers.IO) {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuffer > 0) { "This phone cannot record at $sampleRate Hz" }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, chunkBytes * 4),
        )
        check(recorder.state == AudioRecord.STATE_INITIALIZED) { "Microphone could not be opened" }
        try {
            recorder.startRecording()
            Diagnostics.log("voice", "microphone open at $sampleRate Hz, ${chunkBytes}-byte chunks")
            var total = 0
            while (coroutineContext.isActive) {
                val chunk = ByteArray(chunkBytes)
                var filled = 0
                while (filled < chunkBytes && coroutineContext.isActive) {
                    val n = recorder.read(chunk, filled, chunkBytes - filled)
                    if (n < 0) error("Microphone read failed ($n)")
                    filled += n
                }
                if (filled == chunkBytes) {
                    onChunk(chunk)
                    total += filled
                }
            }
            Diagnostics.log("voice", "microphone closed after $total bytes")
        } finally {
            runCatching { recorder.stop() }
            recorder.release()
        }
    }
}

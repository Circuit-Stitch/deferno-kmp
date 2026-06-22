package com.circuitstitch.deferno.core.speech

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Microphone capture for the whisper engine (#92, ADR-0018): a [Flow] of float32 [-1,1] mono PCM chunks
 * at 16 kHz — exactly whisper's input format. Each engine owns its own capture (there is no shared audio
 * seam, ADR-0018). The audio is **transient**: it flows straight to recognition and is never written to
 * disk, persisted, uploaded, or logged (ADR-0009). Coverage-excluded (device hardware; ADR-0006).
 *
 * The caller must hold `RECORD_AUDIO` before collecting; if it is missing or `AudioRecord` can't start,
 * the flow fails so [WhisperSpeechToText] can surface a [SpeechError.Capture].
 */
internal class MicAudioSource(
    private val sampleRate: Int = 16_000,
    // Read granularity (samples per emitted chunk): smaller = lower-latency, more frequent chunks. The
    // Brain dump spectrum wants ~50 ms (20 Hz) to feel near real-time; streaming dictation keeps the
    // ~200 ms default (whisper just concatenates chunks, so finer is fine but unnecessary). The AudioRecord
    // internal buffer is sized separately (below) and is unaffected.
    private val chunkSamples: Int = sampleRate / 5,
) {
    @SuppressLint("MissingPermission") // RECORD_AUDIO is enforced by the caller (the New surface, L3).
    fun stream(): Flow<FloatArray> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (minBuffer <= 0) {
            close(IllegalStateException("AudioRecord.getMinBufferSize failed: $minBuffer"))
            return@callbackFlow
        }
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, sampleRate), // ≥ ~0.5s headroom
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            close(IllegalStateException("AudioRecord failed to initialize"))
            return@callbackFlow
        }

        val pcm = ShortArray(chunkSamples)
        record.startRecording()
        val reader = launch(Dispatchers.IO) {
            while (isActive) {
                val read = record.read(pcm, 0, pcm.size)
                if (read > 0) {
                    val floats = FloatArray(read) { pcm[it] / 32768f }
                    trySend(floats)
                } else if (read < 0) {
                    close(IllegalStateException("AudioRecord.read error: $read"))
                    break
                }
            }
        }

        awaitClose {
            reader.cancel()
            runCatching { record.stop() }
            record.release()
        }
    }.flowOn(Dispatchers.IO)
}

package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Records the microphone to a 16 kHz mono PCM WAV file for the async Brain dump worker (#150, ADR-0027).
 * It reuses the engine's [MicAudioSource] (the same AudioRecord capture the streaming dictation uses) so
 * there is one mic path, and serialises the take to disk via the pure [WavCodec].
 *
 * Recording runs until the calling coroutine is **cancelled** — the overlay launches [recordTo] in a job
 * and `cancelAndJoin()`s it on Stop, at which point the WAV is finalised (the `finally` write runs under
 * [NonCancellable] so the file is complete even though the coroutine is unwinding). The caller must hold
 * `RECORD_AUDIO` before calling. Privacy (ADR-0009/0018): this is the one place audio touches disk; the
 * worker deletes the file right after transcription, and nothing logs the samples.
 */
class AudioFileRecorder(
    private val sampleRate: Int = WavCodec.SAMPLE_RATE,
) {
    // Internal capture seam — not in the public API (MicAudioSource is module-internal).
    private val source = MicAudioSource(sampleRate)

    suspend fun recordTo(file: File) {
        val chunks = ArrayList<FloatArray>()
        try {
            source.stream().collect { chunks += it }
        } finally {
            withContext(NonCancellable) {
                val total = chunks.sumOf { it.size }
                val all = FloatArray(total)
                var at = 0
                for (chunk in chunks) {
                    chunk.copyInto(all, at)
                    at += chunk.size
                }
                file.writeBytes(WavCodec.encodePcm16(all, sampleRate))
            }
        }
    }
}

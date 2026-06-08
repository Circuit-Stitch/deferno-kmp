package com.circuitstitch.deferno.core.speech

import kotlin.math.sqrt

/**
 * A small, pure energy-based **voice-activity detector** for streaming dictation (ADR-0018). It is fed
 * float32 [-1,1] PCM frames and decides, from short-term RMS energy against an adaptively-estimated
 * noise floor, when an utterance has *ended* (speech followed by enough trailing silence) so the engine
 * can settle a [TranscriptEvent.Final].
 *
 * Deliberately **clock-free and deterministic**: progress is measured in *samples consumed*, never
 * wall-clock time, so it is unit-testable and never a CI timebomb. It is the one piece of the Android
 * whisper engine that is real, portable logic, so it lives in commonMain and **is measured** — the
 * AudioRecord capture and JNI bridge that feed it stay in androidMain (coverage-excluded).
 *
 * Not thread-safe: drive it from the single capture loop.
 */
class EnergyVad(
    private val sampleRate: Int = 16_000,
    /** Analysis frame length; 10 ms at 16 kHz. RMS is computed per whole frame. */
    private val frameSamples: Int = 160,
    /** Trailing silence that ends an utterance, in milliseconds. */
    silenceHangoverMs: Int = 800,
    /** Speech threshold as a multiple of the estimated noise floor. */
    private val thresholdMultiplier: Float = 3.0f,
    /** Initial noise-floor RMS estimate before any audio is seen. */
    private val initialNoiseFloor: Float = 0.01f,
) {
    private val silenceHangoverSamples: Int = sampleRate * silenceHangoverMs / 1000

    private var noiseFloor: Float = initialNoiseFloor
    private var trailingSilenceSamples: Int = 0
    private var sawSpeech: Boolean = false

    /** Whether any speech has been detected since the last [reset]. */
    val hasSpeech: Boolean get() = sawSpeech

    /**
     * True once speech has been seen **and** trailing silence has persisted past the hangover — i.e. the
     * speaker has finished and the engine should settle a Final. Stays false until speech is heard, so a
     * silent lead-in never ends the utterance prematurely.
     */
    val isEndOfUtterance: Boolean get() = sawSpeech && trailingSilenceSamples >= silenceHangoverSamples

    /**
     * Consume a [samples] chunk, updating state frame by frame. A trailing partial frame (shorter than
     * [frameSamples]) is folded into the last whole frame's accounting. Returns [hasSpeech].
     */
    fun accept(samples: FloatArray): Boolean {
        var offset = 0
        while (offset < samples.size) {
            val end = minOf(offset + frameSamples, samples.size)
            val frameLen = end - offset
            val rms = rms(samples, offset, frameLen)

            // Adapt the noise floor: track quickly downward (toward quiet), slowly upward (so speech
            // energy doesn't quickly inflate the floor and mask itself).
            val alpha = if (rms < noiseFloor) 0.05f else 0.001f
            noiseFloor = (1 - alpha) * noiseFloor + alpha * rms

            if (rms > noiseFloor * thresholdMultiplier) {
                sawSpeech = true
                trailingSilenceSamples = 0
            } else {
                trailingSilenceSamples += frameLen
            }
            offset = end
        }
        return sawSpeech
    }

    /** Reset to the pre-utterance state (reuse the detector for the next dictation). */
    fun reset() {
        noiseFloor = initialNoiseFloor
        trailingSilenceSamples = 0
        sawSpeech = false
    }

    private fun rms(samples: FloatArray, offset: Int, length: Int): Float {
        if (length <= 0) return 0f
        var sumSquares = 0.0
        for (i in offset until offset + length) {
            val s = samples[i]
            sumSquares += (s * s).toDouble()
        }
        return sqrt(sumSquares / length).toFloat()
    }
}

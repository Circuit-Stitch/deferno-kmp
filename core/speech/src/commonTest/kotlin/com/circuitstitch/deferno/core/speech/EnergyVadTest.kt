package com.circuitstitch.deferno.core.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The pure energy VAD (ADR-0018): deterministic, sample-counted end-of-utterance detection. */
class EnergyVadTest {

    private val sampleRate = 16_000

    /** A loud-ish tone (amplitude 0.3) — well above the initial noise floor. */
    private fun tone(samples: Int, amplitude: Float = 0.3f): FloatArray =
        FloatArray(samples) { (amplitude * sin(2.0 * PI * 220.0 * it / sampleRate)).toFloat() }

    /** Silence (zeros). */
    private fun silence(samples: Int): FloatArray = FloatArray(samples)

    @Test
    fun silenceAlone_isNeverSpeechOrEndOfUtterance() {
        val vad = EnergyVad(sampleRate)
        vad.accept(silence(sampleRate)) // 1s of silence
        assertFalse(vad.hasSpeech)
        assertFalse(vad.isEndOfUtterance, "a silent lead-in must not end an utterance")
    }

    @Test
    fun speech_isDetected_butNotEndedWhileStillSpeaking() {
        val vad = EnergyVad(sampleRate)
        vad.accept(tone(sampleRate)) // 1s of voice
        assertTrue(vad.hasSpeech)
        assertFalse(vad.isEndOfUtterance, "still speaking — not ended")
    }

    @Test
    fun speechThenShortSilence_underHangover_isNotEnded() {
        val vad = EnergyVad(sampleRate, silenceHangoverMs = 800)
        vad.accept(tone(sampleRate))
        vad.accept(silence(sampleRate / 5)) // 200ms < 800ms hangover
        assertTrue(vad.hasSpeech)
        assertFalse(vad.isEndOfUtterance)
    }

    @Test
    fun speechThenSilencePastHangover_endsTheUtterance() {
        val vad = EnergyVad(sampleRate, silenceHangoverMs = 800)
        vad.accept(tone(sampleRate))
        vad.accept(silence(sampleRate)) // 1s > 800ms hangover
        assertTrue(vad.isEndOfUtterance)
    }

    @Test
    fun reset_returnsToPreUtteranceState() {
        val vad = EnergyVad(sampleRate, silenceHangoverMs = 800)
        vad.accept(tone(sampleRate))
        vad.accept(silence(sampleRate))
        assertTrue(vad.isEndOfUtterance)

        vad.reset()
        assertFalse(vad.hasSpeech)
        assertFalse(vad.isEndOfUtterance)
    }

    @Test
    fun handlesChunksNotAlignedToFrameSize() {
        val vad = EnergyVad(sampleRate, frameSamples = 160, silenceHangoverMs = 500)
        // 1234 isn't a multiple of 160 — the trailing partial frame must still be accounted for.
        vad.accept(tone(1234))
        assertTrue(vad.hasSpeech)
        vad.accept(silence(sampleRate)) // ample trailing silence
        assertTrue(vad.isEndOfUtterance)
    }
}

package com.circuitstitch.deferno.core.speech

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The pure spectrum math behind the Brain dump visualizer: direct DFT into log-spaced ≈110 Hz–3.5 kHz bands. */
class AudioSpectrumTest {

    private val bands = 16

    /** A quiet pure tone at [freqHz] (amplitude kept low so the dB scale doesn't clip neighbouring bands). */
    private fun tone(freqHz: Double, samples: Int = 3200): FloatArray =
        FloatArray(samples) { (0.01 * sin(2.0 * PI * freqHz * it / AudioSpectrum.SAMPLE_RATE)).toFloat() }

    private fun argMax(a: FloatArray): Int = a.indices.maxBy { a[it] }

    @Test
    fun silence_isAllZero() {
        val out = AudioSpectrum.magnitudes(FloatArray(3200), bands)
        assertEquals(bands, out.size)
        assertTrue(out.all { it == 0f }, "silence should produce no spectrum, got ${out.toList()}")
    }

    @Test
    fun output_isBandsLongAndNormalized() {
        val out = AudioSpectrum.magnitudes(tone(300.0), bands)
        assertEquals(bands, out.size)
        assertTrue(out.all { it in 0f..1f }, "every band must be in 0..1, got ${out.toList()}")
    }

    @Test
    fun toneInRange_peaksAtTheNearestInteriorBand() {
        val out = AudioSpectrum.magnitudes(tone(300.0), bands)
        val peak = argMax(out)
        // 300 Hz sits comfortably inside the ≈110 Hz–3.5 kHz log span: a clear interior maximum beating both sides.
        assertTrue(peak in 1 until out.lastIndex, "peak should be interior, was $peak (${out.toList()})")
        assertTrue(out[peak] > out[peak - 1] && out[peak] > out[peak + 1])
    }

    @Test
    fun lowTone_landsLeftOfHighTone() {
        val low = AudioSpectrum.magnitudes(tone(120.0), bands)
        val high = AudioSpectrum.magnitudes(tone(450.0), bands)
        assertTrue(argMax(low) < argMax(high), "a 120 Hz tone must peak left of a 450 Hz tone")
    }

    @Test
    fun shortChunk_doesNotCrash() {
        val out = AudioSpectrum.magnitudes(FloatArray(64) { 0.1f }, bands)
        assertEquals(bands, out.size)
        assertTrue(out.all { it in 0f..1f })
    }

    @Test
    fun emptyChunk_isAllZero() {
        val out = AudioSpectrum.magnitudes(FloatArray(0), bands)
        assertEquals(bands, out.size)
        assertTrue(out.all { it == 0f })
    }
}

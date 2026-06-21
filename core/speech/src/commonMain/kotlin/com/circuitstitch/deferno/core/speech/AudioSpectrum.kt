package com.circuitstitch.deferno.core.speech

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pure audio-spectrum magnitudes for the Brain dump "listening" visualizer. Given a chunk of float32
 * [-1,1] mono PCM at [SAMPLE_RATE], returns [bands] per-band energy levels in `0..1`, log-spaced across
 * the voice-fundamental range [MIN_HZ]..[MAX_HZ] (left bar = low, right bar = high).
 *
 * Deliberately a **direct DFT** evaluated at each band's centre frequency rather than a full FFT: we only
 * draw a handful of bars, so projecting the windowed frame onto exactly those frequencies is both simpler
 * (no power-of-two window, no bit-reversal) and cheaper than computing every bin and discarding most.
 * Pure + deterministic so it is unit-testable like [EnergyVad]; the AudioRecord capture that feeds it
 * stays in androidMain (coverage-excluded). Privacy (ADR-0009/0018): the samples are transient — magnitudes
 * out, samples dropped; nothing here logs or persists audio.
 */
object AudioSpectrum {
    /** Capture rate of the mic source that feeds this (matches `WavCodec.SAMPLE_RATE` / `MicAudioSource`). */
    const val SAMPLE_RATE = 16_000

    /** Far-left band centre — low voice fundamentals. (A2) */
    private const val MIN_HZ = 110.0

    /** Far-right band centre. (A7) */
    private const val MAX_HZ = 3520.0

    /** Analysis window: the most-recent samples of the chunk (~64 ms at 16 kHz — several cycles at 100 Hz). */
    private const val WINDOW = 1024

    /**
     * The dB window mapped to bar height: a band quieter than [FLOOR_DB] reads as silence (no bar), and
     * [CEIL_DB] or louder fills it. Room noise measures around -90..-72 dB here, so the floor sits just
     * above it (clean in silence) while the ceiling is kept low so ordinary speech — which lands in the
     * -45..-60 dB range per band — drives the bars most of the way up. A linear scale buries all of that
     * in the bottom few %, so we map decibels; tighten the window to make the bars more sensitive.
     */
    private const val FLOOR_DB = -70.0
    private const val CEIL_DB = -50.0

    /**
     * Per-band levels in `0..1` for the most-recent [WINDOW] samples of [samples], Hann-windowed and
     * projected onto [bands] log-spaced centre frequencies. Short/empty chunks yield (near-)zero bars
     * rather than throwing.
     */
    fun magnitudes(samples: FloatArray, bands: Int): FloatArray {
        val out = FloatArray(bands)
        if (bands <= 0 || samples.isEmpty()) return out

        val n = minOf(WINDOW, samples.size)
        val start = samples.size - n
        val denom = (n - 1).coerceAtLeast(1)

        // Hann window applied once, reused across every band's projection.
        val windowed = FloatArray(n)
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2.0 * PI * i / denom)
            windowed[i] = (samples[start + i] * w).toFloat()
        }

        for (b in 0 until bands) {
            val k = 2.0 * PI * bandFreq(b, bands) / SAMPLE_RATE
            var re = 0.0
            var im = 0.0
            for (i in 0 until n) {
                val s = windowed[i]
                re += s * cos(k * i)
                im += s * sin(k * i)
            }
            val mag = sqrt(re * re + im * im) / n
            // Map magnitude to the dB window: quiet room noise lands below FLOOR (≈0 bars), voice rides up it.
            val db = 20.0 * log10(mag + 1e-9)
            out[b] = ((db - FLOOR_DB) / (CEIL_DB - FLOOR_DB)).toFloat().coerceIn(0f, 1f)
        }
        return out
    }

    /** Log-spaced centre frequency for band [b] of [bands], from [MIN_HZ] to [MAX_HZ]. */
    private fun bandFreq(b: Int, bands: Int): Double {
        if (bands == 1) return MIN_HZ
        val t = b.toDouble() / (bands - 1)
        return MIN_HZ * exp(t * ln(MAX_HZ / MIN_HZ))
    }
}

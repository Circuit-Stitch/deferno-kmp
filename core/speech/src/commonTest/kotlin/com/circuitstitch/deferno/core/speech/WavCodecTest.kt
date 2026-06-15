package com.circuitstitch.deferno.core.speech

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Round-trip + header tests for [WavCodec] (#150, ADR-0027). This is the only pure-logic link in the
 * record→worker chain (AudioRecord + JNI are device-only), so the PCM16/endianness math is proved here
 * on the JVM gate.
 */
class WavCodecTest {

    @Test
    fun roundTripsSamplesWithinQuantisationError() {
        val samples = floatArrayOf(0f, 0.5f, -0.5f, 0.999f, -0.999f, 0.25f, -0.75f)

        val decoded = WavCodec.decodePcm16(WavCodec.encodePcm16(samples))

        assertEquals(samples.size, decoded.size)
        for (i in samples.indices) {
            // 16-bit quantisation: one LSB = 1/32768 ≈ 3.1e-5.
            assertTrue(
                kotlin.math.abs(samples[i] - decoded[i]) < 1e-4f,
                "sample $i: ${samples[i]} vs ${decoded[i]}",
            )
        }
    }

    @Test
    fun clampsOutOfRangeSamples() {
        val decoded = WavCodec.decodePcm16(WavCodec.encodePcm16(floatArrayOf(2f, -2f)))

        assertTrue(decoded[0] > 0.99f, "over-range +2.0 clamps near +1.0, got ${decoded[0]}")
        assertTrue(decoded[1] < -0.99f, "over-range -2.0 clamps near -1.0, got ${decoded[1]}")
    }

    @Test
    fun writesCanonicalRiffWaveHeader() {
        val wav = WavCodec.encodePcm16(floatArrayOf(0f, 0f), sampleRate = 16_000)

        assertEquals("RIFF", wav.ascii(0, 4))
        assertEquals("WAVE", wav.ascii(8, 4))
        assertEquals("fmt ", wav.ascii(12, 4))
        assertEquals("data", wav.ascii(36, 4))
        assertEquals(16_000, wav.i32le(24)) // sample rate
        assertEquals(1, wav.i16le(22)) // mono
        assertEquals(16, wav.i16le(34)) // bits per sample
        assertEquals(4, wav.i32le(40)) // data size = 2 samples * 2 bytes
        assertEquals(44 + 4, wav.size)
    }

    @Test
    fun emptySamplesProduceHeaderOnlyFile() {
        val wav = WavCodec.encodePcm16(FloatArray(0))
        assertEquals(44, wav.size)
        assertTrue(WavCodec.decodePcm16(wav).isEmpty())
    }

    @Test
    fun decodingTruncatedDataIsSafe() {
        assertTrue(WavCodec.decodePcm16(ByteArray(10)).isEmpty())
    }

    private fun ByteArray.ascii(offset: Int, length: Int): String =
        buildString { for (i in 0 until length) append(this@ascii[offset + i].toInt().toChar()) }

    private fun ByteArray.i16le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or ((this[offset + 1].toInt() and 0xFF) shl 8)

    private fun ByteArray.i32le(offset: Int): Int =
        (this[offset].toInt() and 0xFF) or
            ((this[offset + 1].toInt() and 0xFF) shl 8) or
            ((this[offset + 2].toInt() and 0xFF) shl 16) or
            ((this[offset + 3].toInt() and 0xFF) shl 24)
}

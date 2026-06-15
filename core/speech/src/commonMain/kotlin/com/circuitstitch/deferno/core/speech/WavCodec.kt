package com.circuitstitch.deferno.core.speech

/**
 * Minimal canonical-PCM WAV codec for the Brain dump async path (#150, ADR-0027): 16-bit mono,
 * little-endian, whichever [sampleRate]. It is the one piece of the record→worker chain that is pure
 * logic (no AudioRecord, no JNI), so it lives in commonMain and is unit-tested on the JVM gate while
 * the platform glue ([AudioFileRecorder], [BrainDumpTranscriber]) stays device-only.
 *
 * The codec round-trips float32 [-1,1] mono PCM — exactly the format `MicAudioSource` emits and whisper
 * consumes — through a 44-byte WAV so the recorded take can be handed to WorkManager as a file and
 * decoded back for batch transcription. It writes/reads only the canonical header it produces; it is not
 * a general WAV parser (the file is always one we wrote). Privacy (ADR-0009/0018): the bytes are the
 * transient recording the worker deletes after transcription — never logged.
 */
object WavCodec {
    const val SAMPLE_RATE: Int = 16_000

    private const val HEADER_SIZE = 44
    private const val BITS_PER_SAMPLE = 16
    private const val NUM_CHANNELS = 1
    private const val PCM_FORMAT = 1

    /** Encode float32 [-1,1] mono [samples] as a 16-bit PCM WAV byte array. */
    fun encodePcm16(samples: FloatArray, sampleRate: Int = SAMPLE_RATE): ByteArray {
        val dataSize = samples.size * 2
        val out = ByteArray(HEADER_SIZE + dataSize)
        val byteRate = sampleRate * NUM_CHANNELS * (BITS_PER_SAMPLE / 8)

        ascii(out, 0, "RIFF")
        i32le(out, 4, 36 + dataSize) // RIFF chunk size = 36 + data
        ascii(out, 8, "WAVE")
        ascii(out, 12, "fmt ")
        i32le(out, 16, 16) // fmt chunk size (PCM)
        i16le(out, 20, PCM_FORMAT)
        i16le(out, 22, NUM_CHANNELS)
        i32le(out, 24, sampleRate)
        i32le(out, 28, byteRate)
        i16le(out, 32, NUM_CHANNELS * (BITS_PER_SAMPLE / 8)) // block align
        i16le(out, 34, BITS_PER_SAMPLE)
        ascii(out, 36, "data")
        i32le(out, 40, dataSize)

        var at = HEADER_SIZE
        for (sample in samples) {
            // *32767 (not 32768) so a +1.0 sample doesn't overflow Short.MAX. Symmetric with the
            // /32768f decode below — the half-LSB asymmetry is inaudible and standard.
            val s = (sample.coerceIn(-1f, 1f) * 32767f).toInt()
            out[at] = (s and 0xFF).toByte()
            out[at + 1] = ((s shr 8) and 0xFF).toByte()
            at += 2
        }
        return out
    }

    /** Decode a [wav] produced by [encodePcm16] back to float32 [-1,1] mono samples. */
    fun decodePcm16(wav: ByteArray): FloatArray {
        if (wav.size <= HEADER_SIZE) return FloatArray(0)
        val declared = i32leAt(wav, 40)
        val available = wav.size - HEADER_SIZE
        val dataSize = if (declared in 0..available) declared else available
        val sampleCount = dataSize / 2
        val out = FloatArray(sampleCount)
        var at = HEADER_SIZE
        for (i in 0 until sampleCount) {
            val lo = wav[at].toInt() and 0xFF
            val hi = wav[at + 1].toInt() // sign-extends, preserving negatives
            out[i] = ((hi shl 8) or lo) / 32768f
            at += 2
        }
        return out
    }

    private fun ascii(buf: ByteArray, offset: Int, text: String) {
        for (i in text.indices) buf[offset + i] = text[i].code.toByte()
    }

    private fun i32le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
        buf[offset + 2] = ((value shr 16) and 0xFF).toByte()
        buf[offset + 3] = ((value shr 24) and 0xFF).toByte()
    }

    private fun i16le(buf: ByteArray, offset: Int, value: Int) {
        buf[offset] = (value and 0xFF).toByte()
        buf[offset + 1] = ((value shr 8) and 0xFF).toByte()
    }

    private fun i32leAt(buf: ByteArray, offset: Int): Int =
        (buf[offset].toInt() and 0xFF) or
            ((buf[offset + 1].toInt() and 0xFF) shl 8) or
            ((buf[offset + 2].toInt() and 0xFF) shl 16) or
            ((buf[offset + 3].toInt() and 0xFF) shl 24)
}

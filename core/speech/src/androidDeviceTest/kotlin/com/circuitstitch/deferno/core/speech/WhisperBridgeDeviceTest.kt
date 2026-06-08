package com.circuitstitch.deferno.core.speech

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * On-device native-correctness test for the whisper JNI bridge (#92, ADR-0018): proves the real
 * `libwhisper_jni.so` — compiled from the vendored whisper.cpp for the device ABI — actually transcribes
 * audio on the device. It feeds whisper's canonical `jfk.wav` (16 kHz mono PCM16) straight through
 * [WhisperBridge], bypassing AudioRecord, so the result is deterministic — no flaky live microphone
 * (the emulator mic can't be driven reproducibly).
 *
 * Not part of the headless `check` gate (the JVM can't load the .so); run with
 * `:core:speech:connectedAndroidDeviceTest`. Push the two fixtures to the device first (the ~190 MB
 * model is far too big to bundle; the wav rides along for simplicity):
 *   adb push speech-model-pack/src/main/assets/models/ggml-small.en-q5_1.bin /data/local/tmp/
 *   adb push third_party/whisper.cpp/samples/jfk.wav /data/local/tmp/
 * CI runs this once a real-hardware runner lands (ADR-0006/0018); until then it is validated locally.
 */
@RunWith(AndroidJUnit4::class)
class WhisperBridgeDeviceTest {

    @Test
    fun transcribesJfkSample_onDevice() {
        val modelPath = "/data/local/tmp/$MODEL_FILE"
        assertTrue(
            "Model not found at $modelPath — push it first: " +
                "adb push speech-model-pack/src/main/assets/models/$MODEL_FILE /data/local/tmp/",
            File(modelPath).exists(),
        )
        val wavFile = File("/data/local/tmp/jfk.wav")
        assertTrue(
            "jfk.wav not found at $wavFile — push it first: " +
                "adb push third_party/whisper.cpp/samples/jfk.wav /data/local/tmp/",
            wavFile.exists(),
        )

        val pcm = decodePcm16WavToFloat(wavFile.readBytes())
        assertTrue("jfk.wav decoded to too few samples (${pcm.size})", pcm.size > 16_000)

        val handle = WhisperBridge.initContext(modelPath)
        assertNotEquals("whisper failed to load the model", 0L, handle)
        try {
            val transcript = WhisperBridge.transcribe(handle, pcm, /* numThreads = */ 4)
                .lowercase()
                .trim()
            // The JFK clip: "And so my fellow Americans, ask not what your country can do for you …"
            assertTrue(
                "Unexpected transcript: '$transcript'",
                transcript.contains("ask not what your country"),
            )
        } finally {
            WhisperBridge.freeContext(handle)
        }
    }

    /** Decode a 16-bit PCM mono WAV (44-byte header) to float32 [-1,1] — jfk.wav's exact format. */
    private fun decodePcm16WavToFloat(wav: ByteArray): FloatArray {
        val header = 44
        val sampleCount = (wav.size - header) / 2
        val out = FloatArray(sampleCount)
        var byte = header
        for (i in 0 until sampleCount) {
            val lo = wav[byte].toInt() and 0xFF
            val hi = wav[byte + 1].toInt() // sign-extended high byte
            out[i] = ((hi shl 8) or lo) / 32768f
            byte += 2
        }
        return out
    }

    private companion object {
        const val MODEL_FILE = "ggml-small.en-q5_1.bin"
    }
}

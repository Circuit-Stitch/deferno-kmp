package com.circuitstitch.deferno.core.speech

import io.github.givimad.whisperjni.WhisperContext
import io.github.givimad.whisperjni.WhisperFullParams
import io.github.givimad.whisperjni.WhisperJNI
import io.github.givimad.whisperjni.WhisperSamplingStrategy
import java.nio.file.Path

/**
 * The thin Kotlin face of the desktop whisper engine (#94, ADR-0018) — the JVM counterpart of Android's
 * `WhisperBridge`. Where Android binds raw JNI `external fun`s to a self-built `.so`, the desktop bridge
 * wraps the maintained **`whisper-jni`** Maven artifact, whose jar embeds the prebuilt whisper.cpp native
 * libs for the desktop OSes and loads the right one via [WhisperJNI.loadLibrary] (done once, lazily).
 *
 * It owns no policy: a context is an opaque native handle ([initContext] → [freeContext]); [transcribe]
 * runs a CPU-only, English, on-device pass over a float32 16 kHz mono buffer and returns the concatenated
 * segment text. Capture, VAD, partial/final cadence, model presence, and the "audio never leaves the
 * device" invariant all live in [WhisperSpeechToText]. `whisper-jni` is not reentrant per context, so the
 * engine serializes all calls on one context (its single IO-dispatched [listen] flow). Coverage-excluded
 * (native, desktop-only; ADR-0006).
 */
internal object WhisperBridge {

    /** The single library-loaded `whisper-jni` handle; [WhisperJNI.loadLibrary] is idempotent but guarded. */
    private val whisper: WhisperJNI by lazy {
        WhisperJNI.loadLibrary()      // extracts + loads the bundled native lib for this OS/arch
        WhisperJNI.setLibraryLogger(null) // never log whisper.cpp output (no audio/Transcript logging, ADR-0009)
        WhisperJNI()
    }

    /** Initialize a native context from the model file at [modelPath]; `null` on failure (mirrors Android's 0L). */
    fun initContext(modelPath: String): WhisperContext? =
        runCatching { whisper.init(Path.of(modelPath)) }.getOrNull()

    /** Release a native context. A no-op for `null`; idempotent (the native handle guards a double-free). */
    fun freeContext(context: WhisperContext?) {
        context?.let { runCatching { it.close() } }
    }

    /**
     * Transcribe a float32 [-1,1] 16 kHz mono PCM buffer using [context]; returns the concatenated segment
     * text ("" on failure). English-only, CPU-only, no translation; [numThreads] caps the native threads.
     */
    fun transcribe(context: WhisperContext, audio: FloatArray, numThreads: Int): String {
        val params = WhisperFullParams(WhisperSamplingStrategy.GREEDY).apply {
            language = "en"        // English-only v1 (ADR-0018) — never auto-detect/translate
            translate = false
            nThreads = numThreads
            printProgress = false  // these default true in whisper-jni; silence all native output (ADR-0009)
            printRealtime = false
            printTimestamps = false
            printSpecial = false
        }
        if (whisper.full(context, params, audio, audio.size) != 0) return ""
        val segments = whisper.fullNSegments(context)
        return buildString {
            for (i in 0 until segments) append(whisper.fullGetSegmentText(context, i))
        }.trim() // whisper segment text carries a leading space; trim the concatenation
    }
}

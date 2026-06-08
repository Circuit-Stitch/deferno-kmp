package com.circuitstitch.deferno.core.speech

/**
 * The thin Kotlin face of the native whisper.cpp JNI bridge (#92, ADR-0018). The `external` functions
 * bind to `libwhisper_jni.so` (built from the vendored whisper.cpp submodule by the `:speech-whisper-jni`
 * module, which core:speech's androidMain depends on so the `.so` is packaged). The C symbol names in
 * `whisper_jni.cpp` match this class's package — keep them in lockstep.
 *
 * It owns no policy: a context is an opaque native pointer ([initContext] → [freeContext]); [transcribe]
 * runs a CPU-only, English, on-device pass over a float32 16 kHz mono buffer and returns the concatenated
 * segment text. Capture, VAD, partial/final cadence, model presence, and the "audio never leaves the
 * device" invariant all live in [WhisperSpeechToText]. Calls on one context must be serialized (whisper
 * is not reentrant per context). Coverage-excluded (native, device-only; ADR-0006).
 */
internal object WhisperBridge {
    init {
        System.loadLibrary("whisper_jni")
    }

    /** Initialize a native context from the model file at [modelPath]. Returns 0L on failure. */
    external fun initContext(modelPath: String): Long

    /** Release a native context. A no-op for 0L. */
    external fun freeContext(contextPtr: Long)

    /**
     * Transcribe a float32 [-1,1] 16 kHz mono PCM buffer using [contextPtr]; returns the concatenated
     * segment text ("" on failure). [numThreads] caps the CPU threads used.
     */
    external fun transcribe(contextPtr: Long, audio: FloatArray, numThreads: Int): String
}

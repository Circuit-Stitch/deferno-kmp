package com.circuitstitch.deferno.core.speech

import android.content.Context
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Batch (whole-file) on-device transcription for the async Brain dump worker (#150, ADR-0027). Where the
 * streaming [WhisperSpeechToText] flushes the mic into whisper window-by-window for live partials, this
 * decodes a finished WAV ([AudioFileRecorder] produced it) to one float buffer and runs a single
 * [WhisperBridge.transcribe] pass — slow, but it runs in WorkManager so it survives the overlay closing.
 *
 * It resolves the model with the same [androidWhisperModelLocator] the engine uses (dev sideload →
 * Play Asset Delivery), so a `base.en` push is picked up here too. CPU-bound, so it runs on
 * [Dispatchers.Default] (not the IO pool the live engine uses). Returns the transcript text, or `""` when
 * the model is missing or transcription fails — the worker treats `""` as "no tasks". Privacy
 * (ADR-0009/0018): the returned text is handed to extraction and never logged.
 */
class BrainDumpTranscriber(
    private val context: Context,
    private val threads: Int = DEFAULT_THREADS,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    suspend fun transcribe(wav: File): String = withContext(dispatcher) {
        val modelPath = androidWhisperModelLocator(context).modelPath() ?: return@withContext ""
        val samples = WavCodec.decodePcm16(wav.readBytes())
        if (samples.isEmpty()) return@withContext ""
        val contextPtr = WhisperBridge.initContext(modelPath)
        if (contextPtr == 0L) return@withContext ""
        try {
            WhisperBridge.transcribe(contextPtr, samples, threads)
        } finally {
            WhisperBridge.freeContext(contextPtr)
        }
    }

    private companion object {
        // Matches WhisperSpeechToText's DEFAULT_THREADS — tuned for the 2019 SoC dev device (base.en).
        const val DEFAULT_THREADS = 4
    }
}

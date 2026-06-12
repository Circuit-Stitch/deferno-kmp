package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.currentCoroutineContext

/**
 * The on-device whisper.cpp speech engine for Android (#92, ADR-0018) — the portable baseline and the
 * selector's always-available floor. It captures from the microphone ([MicAudioSource]), runs a pure
 * [EnergyVad] to find the end of an utterance, and drives the native [WhisperBridge] over the accumulated
 * audio: a [TranscriptEvent.Partial] roughly every second for live feedback, then a [TranscriptEvent.Final]
 * once the speaker stops.
 *
 * **Privacy (ADR-0009/0018):** recognition is strictly on-device CPU; the PCM is transient and is never
 * written to disk, persisted, uploaded, or logged — only the emitted Transcript text leaves this engine.
 *
 * **English-only v1 (ADR-0018):** non-English locales report [UnavailableReason.UnsupportedLocale] rather
 * than mis-transcribing. Recognition needs the model present (ADR-0019); until [WhisperModelLocator]
 * resolves it, availability is [UnavailableReason.ModelMissing]. Coverage-excluded (native/device-only,
 * ADR-0006); its pure VAD and the selector around it ARE measured.
 */
internal class WhisperSpeechToText(
    private val modelLocator: WhisperModelLocator,
    private val audioSource: MicAudioSource = MicAudioSource(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val threads: Int = DEFAULT_THREADS,
) : SpeechToText {

    override val id: SpeechEngineId = SpeechEngineId.Whisper

    /** The floor: a higher-ranked native fast path outranks it only when genuinely available (ADR-0018). */
    override val rank: Int = 0

    /** Whisper streams long-form audio, so it is the preferred engine for a continuous Brain dump. */
    override val supportsContinuous: Boolean = true

    override suspend fun availability(locale: String): SpeechAvailability = when {
        !locale.isEnglishLocale() -> SpeechAvailability.Unavailable(UnavailableReason.UnsupportedLocale)
        modelLocator.modelPath() == null -> SpeechAvailability.Unavailable(UnavailableReason.ModelMissing)
        else -> SpeechAvailability.Available
    }

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = flow {
        if (!locale.isEnglishLocale()) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
            return@flow
        }
        val modelPath = modelLocator.modelPath()
        if (modelPath == null) {
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
            return@flow
        }
        val context = WhisperBridge.initContext(modelPath)
        if (context == 0L) {
            emit(TranscriptEvent.Error(SpeechError.Engine))
            return@flow
        }

        val chunks = ArrayList<FloatArray>()
        var accumulatedSamples = 0
        var lastPartialAtSamples = 0
        val vad = EnergyVad(SAMPLE_RATE)

        try {
            audioSource.stream().collect { chunk ->
                currentCoroutineContext().ensureActive()
                chunks += chunk
                accumulatedSamples += chunk.size
                vad.accept(chunk)

                if (vad.isEndOfUtterance) {
                    emit(TranscriptEvent.Final(WhisperBridge.transcribe(context, chunks.flatten(), threads)))
                    throw EndOfUtterance()
                }
                if (accumulatedSamples - lastPartialAtSamples >= SAMPLE_RATE) { // ~1s of new audio
                    emit(TranscriptEvent.Partial(WhisperBridge.transcribe(context, chunks.flatten(), threads)))
                    lastPartialAtSamples = accumulatedSamples
                }
            }
        } catch (_: EndOfUtterance) {
            // Normal completion: the speaker stopped, the Final was already emitted.
        } catch (_: IllegalStateException) {
            // AudioRecord could not capture (permission revoked mid-session, device busy).
            emit(TranscriptEvent.Error(SpeechError.Capture))
        } finally {
            WhisperBridge.freeContext(context)
        }
    }.flowOn(ioDispatcher)

    /** Concatenate the captured chunks into one contiguous buffer for a transcription pass. */
    private fun List<FloatArray>.flatten(): FloatArray {
        val total = sumOf { it.size }
        val out = FloatArray(total)
        var at = 0
        for (chunk in this) {
            chunk.copyInto(out, at)
            at += chunk.size
        }
        return out
    }


    private companion object {
        const val SAMPLE_RATE = 16_000
        const val DEFAULT_THREADS = 4
    }
}

/** Control-flow sentinel that ends the capture loop once the utterance settles (caught locally). */
private class EndOfUtterance : RuntimeException() {
    override fun fillInStackTrace(): Throwable = this // cheap: it is control flow, not an error
}

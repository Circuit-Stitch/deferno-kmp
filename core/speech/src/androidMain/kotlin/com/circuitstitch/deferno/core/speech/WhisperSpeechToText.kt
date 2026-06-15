package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.common.log.Logger
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
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

    // Trace logger for the on-device path (#150). Privacy (ADR-0009/0018): logs only structure —
    // event types, sample/char counts, lifecycle — never the audio or the transcribed text.
    private val log = Logger("Whisper")

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
        log.d { "listen: locale=$locale hint=$continuityHint" }
        if (!locale.isEnglishLocale()) {
            log.w { "listen: locale '$locale' is not English → Unavailable" }
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
            return@flow
        }
        val modelPath = modelLocator.modelPath()
        if (modelPath == null) {
            log.w { "listen: no whisper model on device → Unavailable" }
            emit(TranscriptEvent.Error(SpeechError.Unavailable))
            return@flow
        }
        log.d { "listen: model resolved at $modelPath; initializing whisper context" }
        // The native load (System.loadLibrary in WhisperBridge) + model init can throw — e.g. the
        // libwhisper_jni.so is missing for this ABI, or the file isn't a valid model. Catch it here so a
        // native failure surfaces as a logged, typed Error instead of a silent coroutine death (#150).
        val context = try {
            WhisperBridge.initContext(modelPath)
        } catch (t: Throwable) {
            log.e(t) { "listen: whisper native init threw (missing .so / bad model?)" }
            emit(TranscriptEvent.Error(SpeechError.Engine))
            return@flow
        }
        if (context == 0L) {
            log.e { "listen: initContext returned 0 (could not load the model)" }
            emit(TranscriptEvent.Error(SpeechError.Engine))
            return@flow
        }
        log.d { "listen: whisper context ready; opening microphone" }

        val chunks = ArrayList<FloatArray>()
        var accumulatedSamples = 0
        var lastPartialAtSamples = 0
        var sawAudio = false
        val vad = EnergyVad(SAMPLE_RATE)

        try {
            audioSource.stream().collect { chunk ->
                currentCoroutineContext().ensureActive()
                if (!sawAudio) {
                    sawAudio = true
                    log.d { "listen: first audio chunk (${chunk.size} samples) — mic is live" }
                }
                chunks += chunk
                accumulatedSamples += chunk.size
                vad.accept(chunk)

                if (vad.isEndOfUtterance) {
                    log.d { "listen: FINAL transcribe starting ($accumulatedSamples samples)…" }
                    val mark = TimeSource.Monotonic.markNow()
                    val text = WhisperBridge.transcribe(context, chunks.flatten(), threads)
                    log.d { "listen: FINAL transcribe took ${mark.elapsedNow().inWholeMilliseconds}ms → len=${text.length}" }
                    emit(TranscriptEvent.Final(text))
                    // Utterance (the New form): settle this one and stop. Continuous (the Brain dump):
                    // settle it and keep capturing the next utterance across the pause — reset the buffer
                    // + VAD so the next Final is that utterance alone, not the whole session re-transcribed.
                    // The session runs until the caller cancels the collecting coroutine (stop / teardown).
                    if (continuityHint == ContinuityHint.Utterance) throw EndOfUtterance()
                    chunks.clear()
                    accumulatedSamples = 0
                    lastPartialAtSamples = 0
                    vad.reset()
                    return@collect
                }
                if (accumulatedSamples - lastPartialAtSamples >= SAMPLE_RATE) { // ~1s of new audio
                    log.d { "listen: partial transcribe starting ($accumulatedSamples samples)…" }
                    val mark = TimeSource.Monotonic.markNow()
                    val text = WhisperBridge.transcribe(context, chunks.flatten(), threads)
                    log.d { "listen: partial transcribe took ${mark.elapsedNow().inWholeMilliseconds}ms → len=${text.length}" }
                    emit(TranscriptEvent.Partial(text))
                    lastPartialAtSamples = accumulatedSamples
                }
            }
            log.d { "listen: audio stream ended (sawAudio=$sawAudio)" }
        } catch (_: EndOfUtterance) {
            // Normal completion: the speaker stopped, the Final was already emitted.
            log.v { "listen: utterance settled (Utterance mode), stopping" }
        } catch (c: CancellationException) {
            // Normal stop / teardown — the caller cancelled the collecting coroutine. Must precede the
            // IllegalStateException catch (Kotlin's CancellationException IS-A IllegalStateException) and
            // be rethrown so structured concurrency stays intact.
            log.d { "listen: cancelled (stop / teardown) at $accumulatedSamples samples this utterance" }
            throw c
        } catch (e: IllegalStateException) {
            // AudioRecord could not capture (permission revoked mid-session, device busy).
            log.w(e) { "listen: capture failed (mic revoked / device busy)" }
            emit(TranscriptEvent.Error(SpeechError.Capture))
        } catch (t: Throwable) {
            // A native transcription failure mid-session — surface it instead of dying silently.
            log.e(t) { "listen: transcription failed mid-session" }
            emit(TranscriptEvent.Error(SpeechError.Engine))
        } finally {
            log.v { "listen: freeing whisper context" }
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

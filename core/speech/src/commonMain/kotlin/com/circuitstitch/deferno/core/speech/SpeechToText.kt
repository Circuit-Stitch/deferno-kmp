package com.circuitstitch.deferno.core.speech

import kotlinx.coroutines.flow.Flow
import kotlin.jvm.JvmInline

/**
 * Capability port for on-device speech-to-text (ADR-0018). The shared seam sits at the **[[Transcript]]
 * altitude, not audio**: each engine owns its own microphone capture + VAD internally — there is
 * deliberately no shared audio/PCM abstraction, because the native fast paths are vertically integrated
 * (own mic + recognition) and won't surrender raw audio. An engine recognizes a single locale's speech
 * and streams it as [TranscriptEvent]s.
 *
 * **Privacy invariant (ADR-0009 / ADR-0018):** the audio that produces a Transcript is transient — PCM
 * in memory, **never written to disk, persisted, uploaded, or logged**. Recognition is always on-device;
 * an engine never silently falls back to a cloud recognizer. Only the user-edited Transcript text
 * survives. Implementations must never log audio or Transcript contents.
 *
 * Engines are registered into a `Set<SpeechToText>` multibinding and chosen per [listen] by the
 * [SpeechToTextSelector] (structural never-cloud). [UnavailableSpeechToText] is the always-unavailable
 * floor used where no real engine exists yet.
 */
interface SpeechToText {
    /** A stable id for diagnostics and the engine [[App setting]] (e.g. `whisper`, `android-mlkit`). */
    val id: SpeechEngineId

    /**
     * Static selection rank — higher wins among the engines that are [SpeechAvailability.Available]
     * (ADR-0018). The portable whisper baseline is the **floor** (lowest real rank); a native fast path
     * declares a higher rank, but only outranks whisper when its [availability] confirms readiness.
     */
    val rank: Int

    /**
     * Whether this engine supports **long-form continuous** capture. A short-utterance native recognizer
     * sets this `false`, so the future [[Brain dump]] ([ContinuityHint.Continuous]) prefers the
     * streaming whisper baseline over it (ADR-0018).
     */
    val supportsContinuous: Boolean

    /** Whether this engine can recognize [locale] on-device **right now** (model present, supported locale). */
    suspend fun availability(locale: String): SpeechAvailability

    /**
     * Stream recognition of [locale] speech as [TranscriptEvent]s until the returned [Flow] is cancelled
     * (the caller cancels to stop dictation). The engine captures its own audio and runs its own VAD.
     * [continuityHint] signals whether this is a short [ContinuityHint.Utterance] (v1 Dictation into a
     * field) or a long [ContinuityHint.Continuous] session, which influences engine selection upstream.
     */
    fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent>
}

/**
 * A stable identifier for a speech engine — used in diagnostics and as the value of the device-local
 * speech-engine [[App setting]] ([SpeechEnginePreference], ADR-0018).
 */
@JvmInline
value class SpeechEngineId(val value: String) {
    companion object {
        /** The portable whisper.cpp baseline — the v1 default and the always-available floor (ADR-0018). */
        val Whisper: SpeechEngineId = SpeechEngineId("whisper")

        /**
         * The **"Automatic"** choice surfaced on the Settings Destination (#93): not a real engine, but
         * the device-local preference value meaning *"let the [SpeechToTextSelector] rank-pick whatever is
         * available"*. Because it never matches a registered engine's id, the selector falls through its
         * preference step to the highest-[rank] available engine — exactly the rank-pick this denotes
         * (ADR-0018). The v1 default stays [Whisper] (pinned), with Automatic offered as an opt-in.
         */
        val Automatic: SpeechEngineId = SpeechEngineId("automatic")

        /** The composite [SpeechToTextSelector] itself (the app-facing engine over all registered engines). */
        val Selector: SpeechEngineId = SpeechEngineId("selector")

        /** The no-op [UnavailableSpeechToText] floor (platforms with no real engine yet). */
        val Unavailable: SpeechEngineId = SpeechEngineId("unavailable")
    }
}

/**
 * How long-running the requested recognition is (ADR-0018). Lets the selector prefer a streaming engine
 * for long-form capture over a short-utterance recognizer.
 */
enum class ContinuityHint {
    /** A short, single-utterance dictation — v1's mic-fills-a-field on the **New** create surface. */
    Utterance,

    /** A long-form continuous session — the future **[[Brain dump]]**; prefer a continuous engine. */
    Continuous,
}

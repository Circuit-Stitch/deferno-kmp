package com.circuitstitch.deferno.macos.speech

import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.speech.ContinuityHint
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.speech.SpeechError
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.TranscriptEvent
import com.circuitstitch.deferno.core.speech.UnavailableReason
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * The **in-process dictation port** the macOS Swift app implements (ADR-0029 Phase 2): a thin Swift→Kotlin
 * seam over `SidecarKit.SpeechTranscriber` (on-device `SFSpeechRecognizer`). It is the in-process twin of
 * the JVM desktop's out-of-process Sidecar path (ADR-0024) — same `SidecarKit` Swift sources, but called
 * directly here (no socket, no launchd Helper), so TCC is attributed to **this app's own identity**.
 *
 * Kotlin owns the `Flow` (it's awkward to produce a `kotlinx.coroutines.Flow` from Swift); Swift just opens
 * the mic and pushes text via callbacks. The privacy invariant holds at this seam: only Transcript **text**
 * crosses it — the audio never leaves the recognizer (ADR-0009/0018).
 */
interface NativeDictation {
    /** On-device recognition is ready for [locale] right now (a recognizer exists + supports on-device). */
    fun isAvailable(locale: String): Boolean

    /**
     * Begin streaming. The Swift side requests Speech + mic TCC, opens the mic, and pushes results:
     * [onPartial]* then a single [onFinal] (the utterance settled), or [onError] with a non-PII reason
     * string. Returns a [NativeDictationHandle] the Kotlin `Flow` cancels (stop / collector cancelled).
     */
    fun start(
        locale: String,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit,
    ): NativeDictationHandle
}

/** A handle Kotlin holds and [stop]s when the dictation `Flow` is cancelled (tears the mic down). */
interface NativeDictationHandle {
    fun stop()
}

/**
 * Adapts a Swift [NativeDictation] to the shared [SpeechToText] seam (ADR-0018) the New surface drives.
 * [listen] is a [callbackFlow] over the Swift callbacks; [awaitClose] stops the native session when the
 * collector cancels (the person tapped stop, or the overlay closed).
 */
class NativeSpeechToText(private val native: NativeDictation) : SpeechToText {
    override val id: SpeechEngineId = SpeechEngineId("macos-sfspeech")

    /** Outranks the whisper floor — a native on-device recognizer is the macOS fast path (ADR-0018). */
    override val rank: Int = 100

    /** SFSpeech is a short-utterance recognizer (the New field dictation, not the long-form Brain dump). */
    override val supportsContinuous: Boolean = false

    override suspend fun availability(locale: String): SpeechAvailability =
        if (native.isAvailable(locale)) {
            SpeechAvailability.Available
        } else {
            SpeechAvailability.Unavailable(UnavailableReason.NoEngine)
        }

    override fun listen(locale: String, continuityHint: ContinuityHint): Flow<TranscriptEvent> = callbackFlow {
        val handle = native.start(
            locale = locale,
            onPartial = { trySend(TranscriptEvent.Partial(it)) },
            onFinal = {
                trySend(TranscriptEvent.Final(it))
                close()
            },
            onError = {
                // Kotlin owns diagnostics (the Swift side is a dumb mic reader). Non-PII: the stable reason
                // code, never the audio/transcript. os_log-backed here via the core.common facade (ADR-0029).
                Logger("MacDictation").w { "dictation error: $it" }
                trySend(TranscriptEvent.Error(it.toSpeechError()))
                close()
            },
        )
        awaitClose { handle.stop() }
    }
}

/** Map the Swift transcriber's non-PII reason string to the typed [SpeechError] the New surface renders. */
private fun String.toSpeechError(): SpeechError = when (this) {
    "permission-denied" -> SpeechError.PermissionDenied
    "no-audio-input", "audio-engine-start-failed" -> SpeechError.Capture
    "recognizer-unavailable", "on-device-unsupported", "unsupported-locale" -> SpeechError.Unavailable
    else -> SpeechError.Engine
}

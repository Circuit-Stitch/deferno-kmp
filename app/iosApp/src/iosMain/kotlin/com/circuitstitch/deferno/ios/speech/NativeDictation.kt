package com.circuitstitch.deferno.ios.speech

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
 * The **in-process dictation port** the iOS Swift app implements (#268, ADR-0037) — the iOS twin of the
 * macOS [com.circuitstitch.deferno.macos.speech.NativeDictation] seam. Swift opens the mic and runs Apple's
 * on-device `SFSpeechRecognizer` (iOS 16+, `requiresOnDeviceRecognition`); Kotlin owns the `Flow`.
 *
 * Privacy invariant (ADR-0009/0018): only Transcript **text** crosses this seam — the audio never leaves the
 * recognizer, is never written to disk or logged. Recognition is forced on-device; an engine never silently
 * falls back to a cloud recognizer.
 */
interface NativeDictation {
    /** On-device recognition is ready for [locale] right now (an on-device recognizer + a mic input exist). */
    fun isAvailable(locale: String): Boolean

    /**
     * Begin streaming. Swift requests Speech + mic permission, opens the mic, and pushes results: [onPartial]*
     * (each the full running utterance, not an increment) then a single [onFinal] when the utterance settles,
     * or [onError] with a non-PII reason string. Returns a [NativeDictationHandle] the Kotlin `Flow` cancels
     * (the person tapped stop, or the collector cancelled).
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
 * collector cancels. SFSpeech is a short-utterance recognizer, so [supportsContinuous] is `false` (the
 * long-form Brain dump transcribes from a file in #269, not this live engine).
 */
class NativeSpeechToText(private val native: NativeDictation) : SpeechToText {
    override val id: SpeechEngineId = SpeechEngineId("ios-sfspeech")

    /** Outranks the whisper floor — a native on-device recognizer is the iOS fast path (ADR-0018). */
    override val rank: Int = 100

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
                trySend(TranscriptEvent.Error(it.toSpeechError()))
                close()
            },
        )
        awaitClose { handle.stop() }
    }
}

/** Map the Swift recognizer's non-PII reason string to the typed [SpeechError] the New surface renders. */
private fun String.toSpeechError(): SpeechError = when (this) {
    "permission-denied" -> SpeechError.PermissionDenied
    "no-audio-input", "audio-engine-start-failed" -> SpeechError.Capture
    "recognizer-unavailable", "on-device-unsupported", "unsupported-locale" -> SpeechError.Unavailable
    else -> SpeechError.Engine
}

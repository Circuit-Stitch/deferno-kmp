package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The **wire** form of a dictation event, carried in [SidecarFrame.StreamData.event] on the
 * [SidecarMethods.SubscribeTranscript] stream (ADR-0024). It mirrors core/speech's `TranscriptEvent`
 * but is a *separate, contract-owned* type: the Sidecar module stays a leaf (it does not depend on
 * core/speech), and #119's `SidecarSpeechToText` condenses [TranscriptWire] → `TranscriptEvent` at the
 * edge (ADR-0011). A non-Kotlin Helper (Swift/…) produces the same JSON.
 *
 * **Privacy (ADR-0009 / ADR-0018):** the recognized text is privacy-critical. [Partial]/[Final]
 * **redact the text from [toString]** so a stray log can't leak it; the audio behind it never reaches
 * this seam at all (the Helper emits text, not PCM — the Transcript-altitude seam of ADR-0018).
 */
@Serializable
sealed interface TranscriptWire {

    /** The in-progress best-guess for the current utterance; a consumer **replaces** the prior partial. */
    @Serializable
    @SerialName("partial")
    data class Partial(val text: String) : TranscriptWire {
        override fun toString(): String = "Partial(text=<redacted ${text.length} chars>)"
    }

    /** The settled recognition for the utterance — from here it is ordinary editable text. */
    @Serializable
    @SerialName("final")
    data class Final(val text: String) : TranscriptWire {
        override fun toString(): String = "Final(text=<redacted ${text.length} chars>)"
    }

    /** Recognition could not complete; [reason] is a **non-PII** cause the UI can present gently. */
    @Serializable
    @SerialName("failure")
    data class Failure(val reason: String) : TranscriptWire
}

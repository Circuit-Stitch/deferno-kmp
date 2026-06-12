package com.circuitstitch.deferno.core.speech

/**
 * One streaming event from a [[Dictation]] (ADR-0018). A [[Transcript]] builds up from [Partial]s and
 * settles on a [Final]; recognition that can't complete emits an [Error]. The audio behind these events
 * is transient and never leaves the device (ADR-0009) — only the user-edited [Final] text survives, from
 * which point it is ordinary editable text the person owns.
 */
sealed interface TranscriptEvent {
    /**
     * The in-progress recognition for the current utterance. A consumer **replaces** the prior partial
     * with this text (it is a running best-guess, not an increment to append).
     */
    data class Partial(val text: String) : TranscriptEvent

    /** The settled recognition for the utterance — from here it is ordinary editable text. */
    data class Final(val text: String) : TranscriptEvent

    /** Recognition could not complete; [reason] is a non-PII cause the UI can present gently. */
    data class Error(val reason: SpeechError) : TranscriptEvent
}

/** A non-PII cause for a [TranscriptEvent.Error] (ADR-0018). Never carries audio or Transcript content. */
enum class SpeechError {
    /** No engine could recognize — the chosen engine was unavailable and so was the floor. */
    Unavailable,

    /** Microphone capture failed (permission lost mid-session, device/hardware busy). */
    Capture,

    /** The recognition engine itself failed mid-stream (native error, out of memory). */
    Engine,

    /**
     * The OS permission behind the engine's capture/recognition is **foreclosed** — denied or
     * restricted, settled by real introspection or a real prompt the person answered (#120). It is
     * terminal until flipped in the OS settings surface (a TCC denial never re-prompts), so the UI
     * treats it as permanently denied and offers the settings deep-link, not a retry.
     */
    PermissionDenied,
}

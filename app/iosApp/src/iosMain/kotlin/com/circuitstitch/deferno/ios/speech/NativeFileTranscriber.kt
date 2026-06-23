package com.circuitstitch.deferno.ios.speech

/**
 * The **file-transcription port** the iOS Swift app implements (#269, ADR-0037): transcribe a finalized
 * on-device WAV to text via Apple's iOS-26 `SpeechAnalyzer` + `SpeechTranscriber`. Distinct from the
 * live-mic [NativeDictation] seam (#268) — the Brain dump records the whole take first, then transcribes the
 * file off-line (long-form, no 1-minute utterance cap).
 *
 * Privacy (ADR-0009/0018): on-device recognition only; only the Transcript **text** crosses the seam, never
 * the audio, and nothing is logged. Calls back **exactly once**: [onResult] with the transcript (empty when
 * the recording held no recognizable speech), or [onError] with a non-PII reason. On unavailability
 * (pre-iOS-26, or the locale asset absent), the Kotlin seam treats it as a blank transcript so the Brain dump
 * pipeline salvages — never silently producing nothing.
 */
interface NativeFileTranscriber {
    fun transcribe(
        wavPath: String,
        locale: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    )
}

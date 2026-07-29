package com.circuitstitch.deferno.macos.speech

/**
 * The **file-transcription port** the macOS Swift app implements (#368 Tranche 5, ADR-0029/ADR-0037):
 * transcribe a finalized on-device WAV to text via Apple's macOS-26 `SpeechAnalyzer` + `SpeechTranscriber`.
 * The macOS twin of iOS's seam of the same name (#269), and deliberately **distinct** from the live-mic
 * [NativeDictation] seam (ADR-0029 Phase 2) — #368 §5 names that distinction: `NativeDictation` /
 * `SidecarKit.SpeechTranscriber` is push-based (partials, then one final, short utterances), the wrong shape
 * for a Brain dump, which records the WHOLE take first and then transcribes the file off-line (long-form, no
 * utterance cap).
 *
 * Privacy (ADR-0009/0018): on-device recognition only; only the Transcript **text** crosses the seam, never
 * the audio, and nothing is logged. Calls back **exactly once**: [onResult] with the transcript (empty when
 * the recording held no recognizable speech), or [onError] with a non-PII reason. The Swift implementation is
 * `@available(macOS 26, *)`-gated behind `#if canImport(Speech)`, exactly like `AI/MacInference.swift` — the
 * app's deployment floor is macOS 14 — so a Mac below macOS 26 either isn't injected at all (`null`) or
 * answers [onError] (`MacFileTranscriber` always injects and answers `"unsupported-os"`). The Kotlin seam
 * treats every such case as a blank transcript so the Brain dump pipeline
 * **salvages**: the take is never silently turned into nothing (ADR-0037).
 */
interface NativeFileTranscriber {
    fun transcribe(
        wavPath: String,
        locale: String,
        onResult: (String) -> Unit,
        onError: (String) -> Unit,
    )
}

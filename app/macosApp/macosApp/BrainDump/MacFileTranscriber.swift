import AVFoundation
import Deferno
import Foundation
// **Do NOT add `import SidecarKit` to this file.** SidecarKit vends its own `public final class
// SpeechTranscriber` (helpers/macos/Sources/SidecarKit/Speech/SpeechTranscriber.swift) — the
// `SFSpeechRecognizer` live-mic wrapper `MacDictation` uses. Its initialiser
// (`init?(localeIdentifier:)`) has *different argument labels* from the Speech framework's
// (`init(locale:preset:)`), so an unqualified `SpeechTranscriber(...)` here would not even be reported as
// ambiguous: overload resolution would quietly pick one. `MacDictation.swift:43` disambiguates in the
// other direction (`SidecarKit.SpeechTranscriber`); this file is the mirror image, and belt-and-braces
// every Speech type below is written module-qualified (`Speech.…`) so a future edit that adds the import
// still cannot mis-bind.
#if canImport(Speech)
import Speech
#endif

/// On-device file transcription (#269, ADR-0037; ported to the Mac in #368 Tranche 5b): implements the
/// Kotlin `NativeFileTranscriber` port over macOS 26's `SpeechAnalyzer` + `SpeechTranscriber`, transcribing
/// the finalized Brain dump WAV off-line. Long-form whole-file recognition with no utterance cap.
/// On-device only (ADR-0009/0018): the audio never leaves the Mac and nothing is logged.
///
/// Distinct from the **live-mic** path, `MacDictation` (#120) — that one is `SFSpeechRecognizer` (via
/// SidecarKit) and genuinely needs Speech TCC; this analyzer stack is a different API over a finalized
/// file. See the import note above: the two "SpeechTranscriber" types must never be confused.
///
/// macOS 26+ (the new Speech analysis API); the app deploys to 14.0, so every use is `@available`-guarded
/// behind `#if canImport(Speech)` — the same idiom `MacInference.swift` uses for FoundationModels. Speech
/// itself is an old framework already linked for `MacDictation`, so unlike FoundationModels it needs no
/// `-weak_framework` entry: the compiler weak-imports the macOS-26 symbols on its own.
///
/// On a pre-26 Mac the call reports `unsupported-os`; the Kotlin seam turns that into a **blank transcript**,
/// so the take becomes a Salvage draft in the Inbox rather than being lost (the never-waste-input invariant,
/// ADR-0037). If the locale's on-device model isn't installed it is downloaded once on first use; a
/// missing/undownloadable locale likewise yields blank → salvage.
final class MacFileTranscriber: NativeFileTranscriber {

    func transcribe(
        wavPath: String,
        locale localeId: String,
        onResult: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        // The awaiting Kotlin coroutine resumes on the first callback; serialize so it fires exactly once.
        let once = CallbackOnce(onResult: onResult, onError: onError)
        // `#if` + `if #available` + a trailing fallback, rather than iOS's `guard #available … else` — an
        // early `guard` reads badly wrapped in a `#if`, and this is the shape `MacInference.infer` already
        // uses for the same "macOS 26 API on a 14.0 deployment target" problem.
        #if canImport(Speech)
        if #available(macOS 26, *) {
            // `_Concurrency.Task` — a bare `Task` resolves to the exported `Deferno.Task` domain model.
            _Concurrency.Task {
                do {
                    once.result(try await Self.run(wavPath: wavPath, localeId: localeId))
                } catch {
                    once.error("transcribe-failed")
                }
            }
            return
        }
        #endif
        once.error("unsupported-os")
    }

    #if canImport(Speech)
    @available(macOS 26, *)
    private static func run(wavPath: String, localeId: String) async throws -> String {
        let wanted = Locale(identifier: localeId)
        // Use the device locale if a model supports it, else the closest same-language one; else salvage.
        let supported = await Speech.SpeechTranscriber.supportedLocales
        let chosen = supported.first { $0.identifier(.bcp47) == wanted.identifier(.bcp47) }
            ?? supported.first { $0.language.languageCode == wanted.language.languageCode }
        guard let useLocale = chosen else { return "" }

        // `.transcription` — finalized whole-file transcription (no progressive/volatile partials).
        let transcriber = Speech.SpeechTranscriber(locale: useLocale, preset: .transcription)

        // Ensure the locale's on-device asset is present (first use may download it).
        let installed = await Speech.SpeechTranscriber.installedLocales
        if !installed.contains(where: { $0.identifier(.bcp47) == useLocale.identifier(.bcp47) }) {
            if let request = try await Speech.AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                try await request.downloadAndInstall()
            }
        }

        let analyzer = Speech.SpeechAnalyzer(modules: [transcriber])
        let file = try AVAudioFile(forReading: URL(fileURLWithPath: wavPath))

        // Drain the transcriber's results CONCURRENTLY with feeding the file: the analyzer streams results as
        // it consumes audio and won't complete until told to finish, so collecting must run alongside.
        let collector = _Concurrency.Task { () -> String in
            var out = AttributedString()
            for try await result in transcriber.results where result.isFinal {
                out.append(result.text)
            }
            return String(out.characters)
        }

        if let lastSample = try await analyzer.analyzeSequence(from: file) {
            try await analyzer.finalizeAndFinish(through: lastSample)
        } else {
            try await analyzer.finalizeAndFinishThroughEndOfInput()
        }
        return try await collector.value
    }
    #endif
}

/// Serializes the one-shot result/error callback so the awaiting Kotlin `CompletableDeferred` resumes once.
/// Load-bearing, not defensive tidiness: the Kotlin seam bounds the wait at 120 s
/// (`BrainDumpRecording.kt` — `TRANSCRIBE_TIMEOUT`) and relies on this de-dupe to make a *late* callback
/// harmless (it would otherwise complete an already-orphaned Deferred). File-scoped `private` so it can
/// never collide with another macOS file's helper.
private final class CallbackOnce {
    private let lock = NSLock()
    private var done = false
    private let onResult: (String) -> Void
    private let onError: (String) -> Void

    init(onResult: @escaping (String) -> Void, onError: @escaping (String) -> Void) {
        self.onResult = onResult
        self.onError = onError
    }

    func result(_ text: String) { fire { self.onResult(text) } }
    func error(_ reason: String) { fire { self.onError(reason) } }

    private func fire(_ block: () -> Void) {
        lock.lock()
        let first = !done
        done = true
        lock.unlock()
        if first { block() }
    }
}

import AVFoundation
import Deferno
import Foundation
import Speech

/// On-device file transcription (#269, ADR-0037): implements the Kotlin `NativeFileTranscriber` port over
/// iOS 26's `SpeechAnalyzer` + `SpeechTranscriber`, transcribing the finalized Brain dump WAV off-line.
/// Distinct from the live-mic SFSpeech path (`IosDictation`, #268) — long-form whole-file recognition with no
/// utterance cap. On-device only (ADR-0009/0018): the audio never leaves the phone and nothing is logged.
///
/// iOS 26+ (the new Speech analysis API); on older iOS the call reports an error so the Kotlin seam yields a
/// blank transcript and the take salvages. If the locale's on-device model isn't installed it is downloaded
/// once on first use; a missing/undownloadable locale also yields blank → salvage.
final class IosFileTranscriber: NativeFileTranscriber {

    func transcribe(
        wavPath: String,
        locale localeId: String,
        onResult: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        // The awaiting Kotlin coroutine resumes on the first callback; serialize so it fires exactly once.
        let once = CallbackOnce(onResult: onResult, onError: onError)
        guard #available(iOS 26, *) else { once.error("unsupported-os"); return }
        _Concurrency.Task {
            do {
                once.result(try await Self.run(wavPath: wavPath, localeId: localeId))
            } catch {
                once.error("transcribe-failed")
            }
        }
    }

    @available(iOS 26, *)
    private static func run(wavPath: String, localeId: String) async throws -> String {
        let wanted = Locale(identifier: localeId)
        // Use the device locale if a model supports it, else the closest same-language one; else salvage.
        let supported = await SpeechTranscriber.supportedLocales
        let chosen = supported.first { $0.identifier(.bcp47) == wanted.identifier(.bcp47) }
            ?? supported.first { $0.language.languageCode == wanted.language.languageCode }
        guard let useLocale = chosen else { return "" }

        // `.transcription` — finalized whole-file transcription (no progressive/volatile partials).
        let transcriber = SpeechTranscriber(locale: useLocale, preset: .transcription)

        // Ensure the locale's on-device asset is present (first use may download it).
        let installed = await SpeechTranscriber.installedLocales
        if !installed.contains(where: { $0.identifier(.bcp47) == useLocale.identifier(.bcp47) }) {
            if let request = try await AssetInventory.assetInstallationRequest(supporting: [transcriber]) {
                try await request.downloadAndInstall()
            }
        }

        let analyzer = SpeechAnalyzer(modules: [transcriber])
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
}

/// Serializes the one-shot result/error callback so the awaiting Kotlin `CompletableDeferred` resumes once.
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

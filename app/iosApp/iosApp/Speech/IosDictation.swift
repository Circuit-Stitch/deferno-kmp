import AVFoundation
import Deferno
import Foundation
import Speech

/// On-device dictation (#268, ADR-0037) — implements the shared `NativeDictation` port over Apple's
/// `SFSpeechRecognizer` with `requiresOnDeviceRecognition` (iOS 16+; the audio never leaves the phone —
/// ADR-0009/0018). The iOS twin of macOS `MacDictation`; Kotlin owns the `Flow`, this just opens the mic and
/// pushes Transcript text via callbacks. The New surface's per-field mic drives it (#92).
///
/// (Short-utterance field dictation only — `SFSpeechRecognizer` covers every supported iOS. The long-form
/// Brain dump transcribes from a finalized WAV file in #269, a separate path, not this live engine.)
final class IosDictation: NativeDictation {

    func isAvailable(locale: String) -> Bool {
        // An on-device recognizer must exist for this locale...
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: locale)),
              recognizer.supportsOnDeviceRecognition else {
            return false
        }
        // ...AND there must be a real mic input route, else `audioEngine.start()` would only fail. Reading
        // the route needs no TCC; permission is still requested on first capture.
        return AVAudioSession.sharedInstance().isInputAvailable
    }

    func start(
        locale: String,
        onPartial: @escaping (String) -> Void,
        onFinal: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) -> NativeDictationHandle {
        let handle = DictationHandle()
        // Speech-recognition TCC first, then mic TCC. A denial is terminal (TCC never re-prompts), so it
        // surfaces as `permission-denied`, which the New surface renders as the "enable it in Settings" state.
        SFSpeechRecognizer.requestAuthorization { status in
            guard status == .authorized else { onError("permission-denied"); return }
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                guard granted else { onError("permission-denied"); return }
                // AVAudioEngine setup (installTap + start) MUST run on the main thread — configuring it off
                // the auth callback's background queue raises an NSException → a Kotlin/Native abort.
                DispatchQueue.main.async {
                    handle.begin(locale: locale, onPartial: onPartial, onFinal: onFinal, onError: onError)
                }
            }
        }
        return handle
    }
}

/// Owns the recognizer, request, task and mic engine for one dictation, so the Kotlin `Flow`'s cancellation
/// tears the mic down — even when `stop()` races ahead of the auth callback that calls `begin()`.
final class DictationHandle: NativeDictationHandle {
    private let lock = NSLock()
    private let engine = AVAudioEngine()
    private var recognizer: SFSpeechRecognizer?
    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var stopped = false
    private var running = false

    /// Open the mic and begin on-device recognition. Runs on the main thread (see `start`).
    func begin(
        locale: String,
        onPartial: @escaping (String) -> Void,
        onFinal: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        lock.lock()
        if stopped { lock.unlock(); return } // stop() already ran while we were authorizing
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: locale)),
              recognizer.isAvailable else {
            lock.unlock(); onError("recognizer-unavailable"); return
        }
        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true // structural never-cloud (ADR-0018)
        self.recognizer = recognizer
        self.request = request

        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.record, mode: .measurement, options: [])
            try session.setActive(true, options: [])
        } catch {
            lock.unlock(); onError("audio-engine-start-failed"); return
        }

        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            self?.request?.append(buffer)
        }
        engine.prepare()
        do {
            try engine.start()
        } catch {
            input.removeTap(onBus: 0)
            self.request = nil
            self.recognizer = nil
            lock.unlock(); onError("audio-engine-start-failed"); return
        }
        running = true

        // resultHandler fires on an arbitrary queue; the callbacks resume the Kotlin coroutine (its
        // `trySend` is thread-safe). On a settled or failed utterance the task ends and the mic tears down.
        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            if let result = result {
                let text = result.bestTranscription.formattedString
                if result.isFinal {
                    onFinal(text)
                    self?.stop()
                } else {
                    onPartial(text)
                }
            } else if error != nil {
                onError("recognition-failed")
                self?.stop()
            }
        }
        lock.unlock()
    }

    func stop() {
        let teardown: () -> Void = { [self] in
            lock.lock()
            stopped = true
            let wasRunning = running
            running = false
            task?.cancel()
            task = nil
            request?.endAudio()
            request = nil
            recognizer = nil
            lock.unlock()
            if wasRunning {
                engine.inputNode.removeTap(onBus: 0)
                engine.stop()
                try? AVAudioSession.sharedInstance().setActive(false, options: [])
            }
        }
        // The Kotlin dictation flow collects on `Dispatchers.Main`, so `awaitClose`→stop() runs on main;
        // run the AVAudioEngine teardown inline there. The `main.sync` branch handles the result-handler's
        // off-main `stop()` (on final/error) without a main→main self-deadlock.
        if Thread.isMainThread { teardown() } else { DispatchQueue.main.sync(execute: teardown) }
    }
}

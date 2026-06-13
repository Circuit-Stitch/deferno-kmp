import AVFoundation
import Deferno
import Foundation
import SidecarKit
import Speech

/// In-process dictation (ADR-0029 Phase 2): implements the shared `NativeDictation` port over SidecarKit's
/// on-device `SpeechTranscriber` (`SFSpeechRecognizer`; the audio never leaves the Mac — ADR-0009/0018).
/// The same Swift sources the launchd Sidecar Helper uses (ADR-0024), but called **directly** here — no
/// socket, no Helper — so Speech/mic TCC is attributed to *this app's own identity* (its Info.plist usage
/// strings). Kotlin owns the `Flow`; this just opens the mic and pushes Transcript text via callbacks.
final class MacDictation: NativeDictation {

    func isAvailable(locale: String) -> Bool {
        SFSpeechRecognizer(locale: Locale(identifier: locale))?.supportsOnDeviceRecognition ?? false
    }

    func start(
        locale: String,
        onPartial: @escaping (String) -> Void,
        onFinal: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) -> NativeDictationHandle {
        let handle = TranscriberHandle()
        // Request Speech + mic TCC first (the View shows the mic; the OS prompt fires here on first use).
        // A macOS TCC denial is terminal — it never re-prompts — so it surfaces as `permission-denied`,
        // which the New surface renders as the "enable it in System Settings" state (#120).
        ensureAuthorized { granted in
            guard granted else { onError("permission-denied"); return }
            // AVAudioEngine mic setup (installTapOnBus + start) MUST run on the main thread. The TCC
            // completion fires on a background xpc queue, and configuring the engine there raises an
            // NSException — which Kotlin/Native's terminate handler turns into an abort (the Phase-2
            // crash). Hop to main before touching the audio engine.
            DispatchQueue.main.async {
                // Qualified: macOS 26's Speech framework also vends a `SpeechTranscriber`, so name SidecarKit's.
                guard let transcriber = SidecarKit.SpeechTranscriber(localeIdentifier: locale) else {
                    onError("unsupported-locale"); return
                }
                guard handle.adopt(transcriber) else { return } // stop already ran while we were authorizing
                transcriber.start(onEvent: { event in
                    switch event {
                    case .partial(let text): onPartial(text)
                    case .final(let text): onFinal(text)
                    case .failure(let reason): onError(reason)
                    @unknown default: onError("recognition-failed")
                    }
                }, onEnd: {})
            }
        }
        return handle
    }

    /// Speech then mic — both must be `granted`; `notDetermined` prompts once, anything else is a denial.
    /// Reused from SidecarKit so the TCC plumbing is the one the Helper already ships (ADR-0024).
    private func ensureAuthorized(_ done: @escaping (Bool) -> Void) {
        SidecarPermissions.requestSpeech { speech in
            guard speech == .granted else { done(false); return }
            SidecarPermissions.requestMicrophone { mic in done(mic == .granted) }
        }
    }
}

/// Holds the (asynchronously-created) `SpeechTranscriber` so the Kotlin `Flow`'s cancellation tears the mic
/// down — even when stop races ahead of the TCC callback that creates the transcriber.
final class TranscriberHandle: NativeDictationHandle {
    private let lock = NSLock()
    private var transcriber: SidecarKit.SpeechTranscriber?
    private var stopped = false

    /// Adopt the transcriber once authorized; returns false (and stops it) if `stop()` already ran.
    func adopt(_ transcriber: SidecarKit.SpeechTranscriber) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if stopped {
            transcriber.stop()
            return false
        }
        self.transcriber = transcriber
        return true
    }

    func stop() {
        lock.lock()
        let transcriber = self.transcriber
        self.transcriber = nil
        stopped = true
        lock.unlock()
        transcriber?.stop()
    }
}

import Foundation
import AVFoundation
import Speech

/// On-device dictation for the Sidecar `subscribeTranscript` stream (ADR-0024 / ADR-0018): an
/// `AVAudioEngine` mic tap feeds an `SFSpeechAudioBufferRecognitionRequest` pinned to **on-device**
/// recognition, and the recognizer's results are surfaced as `TranscriptEvent`s (text, never PCM — the
/// audio never crosses the socket seam, ADR-0009).
///
/// Lifecycle: `start` opens the mic + recognition and streams `partial`* then a single `final` (or a
/// `failure`), after which `onEnd` fires once. `stop` (client cancel / teardown) tears the mic down
/// immediately and fires `onEnd`. Everything funnels through one serial callback queue so results arrive
/// in order off the realtime audio thread; the tap itself only appends buffers.
public final class SpeechTranscriber {

    private let recognizer: SFSpeechRecognizer
    private let audioEngine = AVAudioEngine()
    private let callbackQueue: OperationQueue

    private var request: SFSpeechAudioBufferRecognitionRequest?
    private var task: SFSpeechRecognitionTask?
    private var tapInstalled = false

    private let lock = NSLock()
    private var finished = false
    private var onEnd: (() -> Void)?

    /// Fails (`nil`) only if the locale is genuinely unsupported by the OS.
    public init?(localeIdentifier: String = "en-US") {
        guard let recognizer = SFSpeechRecognizer(locale: Locale(identifier: localeIdentifier)) else {
            return nil
        }
        self.recognizer = recognizer
        let queue = OperationQueue()
        queue.name = "com.circuitstitch.deferno.sidecar.speech"
        queue.maxConcurrentOperationCount = 1 // serial — ordered results, off the audio thread
        self.callbackQueue = queue
        recognizer.queue = queue // else result callbacks default to the (un-serviced) main queue
    }

    /// True iff the recognizer can run fully on-device (the only mode this helper uses — audio stays
    /// on the Mac). When false, `start` reports a `failure` rather than falling back to a network engine.
    public var supportsOnDeviceRecognition: Bool { recognizer.supportsOnDeviceRecognition }

    /// Begin streaming. `onEvent` delivers `partial`/`final`/`failure`; `onEnd` fires exactly once when
    /// the stream is over (natural completion, failure, or `stop`). Callbacks arrive on the serial queue.
    public func start(onEvent: @escaping (TranscriptEvent) -> Void, onEnd: @escaping () -> Void) {
        lock.lock()
        self.onEnd = onEnd
        lock.unlock()

        guard recognizer.isAvailable else {
            emitFailureAndEnd("recognizer-unavailable", onEvent)
            return
        }
        guard recognizer.supportsOnDeviceRecognition else {
            emitFailureAndEnd("on-device-unsupported", onEvent)
            return
        }

        let request = SFSpeechAudioBufferRecognitionRequest()
        request.shouldReportPartialResults = true
        request.requiresOnDeviceRecognition = true // audio never leaves the device (ADR-0009/0018)
        self.request = request

        let input = audioEngine.inputNode
        let format = input.outputFormat(forBus: 0)
        // Guard against a zero/invalid input format (no input device) — installing a tap with it traps.
        guard format.sampleRate > 0, format.channelCount > 0 else {
            emitFailureAndEnd("no-audio-input", onEvent)
            return
        }
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak request] buffer, _ in
            request?.append(buffer) // realtime audio thread — append only, no socket work here
        }
        tapInstalled = true

        audioEngine.prepare()
        do {
            try audioEngine.start()
        } catch {
            emitFailureAndEnd("audio-engine-start-failed", onEvent)
            return
        }

        task = recognizer.recognitionTask(with: request) { [weak self] result, error in
            guard let self else { return }
            if let result {
                let text = result.bestTranscription.formattedString
                if result.isFinal {
                    onEvent(.final(text: text))
                    self.finish()
                    return
                } else {
                    onEvent(.partial(text: text))
                }
            }
            if error != nil {
                // A cancel also surfaces as an error — only report a failure if we weren't asked to stop.
                if !self.isFinished {
                    onEvent(.failure(reason: "recognition-failed"))
                }
                self.finish()
            }
        }
    }

    /// Stop immediately (client cancelled the stream, or the connection is tearing down): release the mic
    /// and end recognition. Idempotent; fires `onEnd` once.
    public func stop() {
        finish()
    }

    private var isFinished: Bool {
        lock.lock(); defer { lock.unlock() }
        return finished
    }

    private func emitFailureAndEnd(_ reason: String, _ onEvent: @escaping (TranscriptEvent) -> Void) {
        onEvent(.failure(reason: reason))
        finish()
    }

    /// Tear down the mic + recognition exactly once and fire `onEnd`.
    private func finish() {
        lock.lock()
        if finished {
            lock.unlock()
            return
        }
        finished = true
        let end = onEnd
        onEnd = nil
        lock.unlock()

        if tapInstalled {
            audioEngine.inputNode.removeTap(onBus: 0)
            tapInstalled = false
        }
        if audioEngine.isRunning {
            audioEngine.stop()
        }
        request?.endAudio()
        task?.cancel()
        request = nil
        task = nil

        end?()
    }
}

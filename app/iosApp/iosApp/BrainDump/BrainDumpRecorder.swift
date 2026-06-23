import AVFoundation
import Deferno
import SwiftUI

/// The iOS Brain dump **recorder** (#267, ADR-0037) — the single mic owner for the overlay. It implements
/// the shared `NativeAudioRecorder` Kotlin port (Kotlin owns the WAV path + the pipeline) and, as an
/// `ObservableObject`, publishes the live `levels` the `BrainDumpView` spectrum renders. ONE `AVAudioEngine`
/// drives both the WAV file and the spectrum, so there is no second mic tap contending on the shared
/// `AVAudioSession` (the View no longer runs its own meter). Privacy (ADR-0009/0018): the audio is written
/// only to the on-device WAV the pipeline consumes; nothing here is logged.
final class BrainDumpRecorder: ObservableObject, NativeAudioRecorder {
    @Published var levels: [CGFloat] = Array(repeating: 0.05, count: 28)

    private let engine = AVAudioEngine()
    private var file: AVAudioFile?
    private var running = false

    /// Open the mic and stream 16-bit PCM to the WAV at `filePath`. `AVAudioEngine` setup MUST run on the
    /// main thread (configuring it off-main raises an NSException → a Kotlin/Native abort), so hop there
    /// first. The caller (the overlay) has already been granted RECORD_AUDIO before this runs. If the engine
    /// can't open despite permission (audio-session interruption, no input route, format mismatch), `onFailed`
    /// fires so the Kotlin seam can surface the shared Failed state instead of a dead mic (Android parity).
    func start(filePath: String, onFailed: @escaping () -> Void) {
        DispatchQueue.main.async { [self] in
            guard !running else { return }
            let session = AVAudioSession.sharedInstance()
            // `.allowBluetooth` lets the input route use a connected Bluetooth (AirPods) mic over HFP — without
            // it the session can't open an input while AirPods are connected (e.g. right after a call), which
            // is exactly the "Couldn't record" the user hit. `.default` mode is the most route-compatible for
            // voice capture (`.measurement` is finicky over Bluetooth HFP). Built-in mic still works unchanged.
            try? session.setCategory(.record, mode: .default, options: [.allowBluetooth])
            try? session.setActive(true, options: [])

            let input = engine.inputNode
            let format = input.outputFormat(forBus: 0)
            // The mic can be unavailable even with permission — an active phone/VoIP call owns the input route,
            // or no input route resolves. Then this format collapses to 0 Hz / 0 channels, and installing a tap
            // (or opening an AVAudioFile) with it raises an AVFoundation NSException: an uncatchable abort, not a
            // Swift throw `try?` can swallow. Treat it as a failed start so the Kotlin seam surfaces the shared
            // Failed state (the salvage path) instead of crashing.
            guard format.sampleRate > 0, format.channelCount > 0 else {
                try? session.setActive(false, options: [])
                onFailed()
                return
            }
            // A standard PCM16 WAV at the mic's native rate/channels; AVAudioFile converts the float32 tap
            // buffers to 16-bit on write. (#269 resamples to the transcriber's format; #267 only needs a
            // durable, retainable recording.)
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: format.sampleRate,
                AVNumberOfChannelsKey: format.channelCount,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
            ]
            file = try? AVAudioFile(forWriting: URL(fileURLWithPath: filePath), settings: settings)

            input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                guard let self else { return }
                try? self.file?.write(from: buffer)
                self.publishLevel(buffer)
            }
            engine.prepare()
            running = (try? engine.start()) != nil
            if !running {
                file = nil // engine didn't open — leave no half-open file
                engine.inputNode.removeTap(onBus: 0)
                onFailed()
            }
        }
    }

    /// Tear the mic down and finalize the WAV. The Kotlin seam runs on the main thread (the Decompose
    /// component context is `Dispatchers.Main`), so the `isMainThread` branch — running the teardown inline —
    /// is the real path; it finalizes the WAV synchronously before the seam launches the pipeline that reads
    /// its bytes. The `main.sync` fallback handles any off-main caller without a main→main self-deadlock.
    func stop() {
        let teardown: () -> Void = { [self] in
            guard running else { file = nil; return }
            engine.inputNode.removeTap(onBus: 0)
            engine.stop()
            file = nil // closing the AVAudioFile finalizes the WAV header
            try? AVAudioSession.sharedInstance().setActive(false, options: [])
            running = false
            levels = Array(repeating: 0.05, count: 28)
        }
        if Thread.isMainThread { teardown() } else { DispatchQueue.main.sync(execute: teardown) }
    }

    private func publishLevel(_ buffer: AVAudioPCMBuffer) {
        guard let channel = buffer.floatChannelData?[0] else { return }
        let count = Int(buffer.frameLength)
        guard count > 0 else { return }
        var sum: Float = 0
        for i in 0..<count { let s = channel[i]; sum += s * s }
        let rms = sqrt(sum / Float(count))
        // Mic input is quiet — scale + clamp to a lively 0…1 bar height (the old meter's mapping).
        let level = CGFloat(min(1, max(0.05, CGFloat(rms) * 14)))
        DispatchQueue.main.async { [weak self] in
            guard let self else { return }
            var next = self.levels
            next.removeFirst()
            next.append(level)
            self.levels = next
        }
    }
}

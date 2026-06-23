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
    /// Per-bar `0…1` levels — low frequencies on the left, high on the right. Computed by the shared,
    /// cross-platform `AudioSpectrum` (core/speech) — the same call Android makes — so the two platforms render
    /// identically; this class only owns the iOS mic and the 16 kHz downsample that spectrum expects.
    @Published var levels: [CGFloat] = Array(repeating: 0.05, count: BrainDumpRecorder.barCount)

    private static let barCount = 16
    // `AudioSpectrum` analyses 16 kHz mono PCM (matches its `SAMPLE_RATE`); we downsample the mic to feed it.
    private static let spectrumRate = 16_000.0
    private static let window = 1024  // ~64 ms at 16 kHz — the rolling DFT window AudioSpectrum reads (its WINDOW)

    private let engine = AVAudioEngine()
    private var file: AVAudioFile?
    private var running = false
    private var converter: AVAudioConverter?  // mic-rate → 16 kHz mono, spectrum (display) only — NOT the WAV
    private var ring = [Float](repeating: 0, count: BrainDumpRecorder.window)  // rolling 16 kHz analysis window

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
            // is exactly the "Couldn't record" the user hit. `.playAndRecord` + `.voiceChat` is what Apple's
            // Voice Processing I/O (below) needs, and `.voiceChat` is Bluetooth-HFP-friendly (unlike `.measurement`).
            try? session.setCategory(.playAndRecord, mode: .voiceChat, options: [.allowBluetooth])
            try? session.setActive(true, options: [])

            let input = engine.inputNode
            // Apple's Voice Processing I/O: the OS DSP does mic-array noise suppression + echo cancellation,
            // stripping steady background (an AC hum, a fan) before we ever see the samples — no custom filtering,
            // and it engages the system Voice Isolation mic mode. AGC off so it denoises without pumping the
            // recording's levels. `try?`: if VPIO can't enable (route/HW), fall back to raw input, don't fail.
            try? input.setVoiceProcessingEnabled(true)
            input.isVoiceProcessingAGCEnabled = false
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
            // A throwaway mic-rate → 16 kHz mono converter feeding ONLY the spectrum (display); the WAV below
            // keeps the mic's native rate. nil-safe — if it can't build, the bars just stay idle.
            converter = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.spectrumRate,
                                      channels: 1, interleaved: false).flatMap { AVAudioConverter(from: format, to: $0) }
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
            levels = Array(repeating: 0.05, count: Self.barCount)
            converter = nil
            ring = [Float](repeating: 0, count: Self.window)
        }
        if Thread.isMainThread { teardown() } else { DispatchQueue.main.sync(execute: teardown) }
    }

    /// Downsample the buffer to 16 kHz mono and hand a rolling ~64 ms window to the shared `AudioSpectrum` (the
    /// same direct-DFT-per-bar + dB-window code Android runs), then publish the resulting `0…1` levels. Display
    /// only — the WAV keeps the mic's native rate.
    private func publishLevel(_ buffer: AVAudioPCMBuffer) {
        guard let converter else { return }
        // Resample this buffer to 16 kHz mono. The streaming `.noDataNow` block keeps the converter's filter
        // state across buffers (no per-buffer discontinuity); output frame count ≈ input × 16k/micRate.
        let cap = AVAudioFrameCount(Double(buffer.frameLength) * Self.spectrumRate / buffer.format.sampleRate) + 16
        guard let out = AVAudioPCMBuffer(pcmFormat: converter.outputFormat, frameCapacity: cap) else { return }
        var consumed = false
        var err: NSError?
        converter.convert(to: out, error: &err) { _, status in
            if consumed { status.pointee = .noDataNow; return nil }
            consumed = true
            status.pointee = .haveData
            return buffer
        }
        guard err == nil, let src = out.floatChannelData?[0], out.frameLength > 0 else { return }

        // Slide the new 16 kHz samples into the rolling window (oldest out, newest in) so AudioSpectrum always
        // sees a full, stable window regardless of how many frames each convert() yields.
        let w = ring.count, m = Int(out.frameLength)
        if m >= w {
            for i in 0..<w { ring[i] = src[m - w + i] }
        } else {
            for i in 0..<(w - m) { ring[i] = ring[i + m] }
            for i in 0..<m { ring[w - m + i] = src[i] }
        }

        // Shared, cross-platform spectrum: direct DFT at each bar's centre frequency + dB-window mapping → 0…1.
        let samples = KotlinFloatArray(size: Int32(w))
        for i in 0..<w { samples.set(index: Int32(i), value: ring[i]) }
        let mags = AudioSpectrum.shared.magnitudes(samples: samples, bands: Int32(Self.barCount))
        var bars = [CGFloat](repeating: 0, count: Self.barCount)
        for i in 0..<Self.barCount { bars[i] = CGFloat(mags.get(index: Int32(i))) }

        DispatchQueue.main.async { [weak self] in self?.levels = bars }
    }
}

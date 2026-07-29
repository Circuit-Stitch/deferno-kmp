import AVFoundation
import Deferno
import SwiftUI

/// The macOS Brain dump **recorder** (#267, ADR-0037; ported to the Mac in #368 Tranche 5b) — the single mic
/// owner for the overlay. It implements the shared `NativeAudioRecorder` Kotlin port (Kotlin owns the WAV
/// path + the pipeline) and, as an `ObservableObject`, publishes the live `levels` the `BrainDumpView`
/// spectrum renders. ONE `AVAudioEngine` drives both the WAV file and the spectrum — one engine, one tap —
/// so the View never opens a second one contending for the same input device (the View runs no meter of its
/// own). Privacy (ADR-0009/0018): the audio is written only to the on-device WAV the pipeline consumes;
/// nothing here is logged.
///
/// The near-verbatim twin of iosApp's `BrainDumpRecorder` — same field names, same order, so the two read
/// clean side by side. Every macOS divergence is *forced* by the platform and is marked at its site:
///
///  1. **No `AVAudioSession`.** The class is `API_UNAVAILABLE(macos)`; iOS's category/route block is deleted,
///     not translated (`start`, and again in `stop`).
///  2. **An explicit no-mic pre-check.** iOS's zero-format guard fires when no route resolves; on macOS it
///     never does (`start`).
///  3. **The tap install is wrapped in `DFNExceptionCatcher`.** With (2) unable to guarantee a sane format,
///     the Obj-C `@try/@catch` shim is the real guard against an `NSException` → Kotlin/Native `abort()`.
///
/// Voice Processing I/O, the WAV settings, the spectrum downsample and `stop`'s synchronous-finalize
/// contract all port unchanged.
final class MacBrainDumpRecorder: ObservableObject, NativeAudioRecorder {
    /// Per-bar `0…1` levels — low frequencies on the left, high on the right. Computed by the shared,
    /// cross-platform `AudioSpectrum` (core/speech) — the same call Android and iOS make — so all three
    /// platforms render identically; this class only owns the Mac mic and the 16 kHz downsample that
    /// spectrum expects.
    @Published var levels: [CGFloat] = Array(repeating: 0.05, count: MacBrainDumpRecorder.barCount)

    private static let barCount = 16
    // `AudioSpectrum` analyses 16 kHz mono PCM (matches its `SAMPLE_RATE`); we downsample the mic to feed it.
    private static let spectrumRate = 16_000.0
    private static let window = 1024  // ~64 ms at 16 kHz — the rolling DFT window AudioSpectrum reads (its WINDOW)

    private let engine = AVAudioEngine()
    private var file: AVAudioFile?
    private var running = false
    private var converter: AVAudioConverter?  // mic-rate → 16 kHz mono, spectrum (display) only — NOT the WAV
    private var ring = [Float](repeating: 0, count: MacBrainDumpRecorder.window)  // rolling 16 kHz analysis window

    /// Open the mic and stream 16-bit PCM to the WAV at `filePath`. `AVAudioEngine` setup MUST run on the
    /// main thread (configuring it off-main raises an NSException → a Kotlin/Native abort — the same trap
    /// `MacDictation.start` hops to main to avoid), so hop there first. The caller (the overlay) has already
    /// been granted mic TCC before this runs. If the engine can't open despite permission (no input device,
    /// device taken, format mismatch), `onFailed` fires so the Kotlin seam can surface the shared Failed
    /// state instead of a dead mic (Android/iOS parity).
    func start(filePath: String, onFailed: @escaping () -> Void) {
        DispatchQueue.main.async { [self] in
            guard !running else { return }

            // DIVERGENCE 1 — a DELETION, not a missing port. iOS opens an `AVAudioSession` here
            // (`.playAndRecord` + `.voiceChat` + `.allowBluetoothHFP`, then `setActive(true)`) to win an
            // input route, notably so a connected AirPods mic can be used over HFP. macOS has no such class
            // at all — `AVAudioSession` is `API_UNAVAILABLE(macos)` — and no equivalent to reach for:
            // CoreAudio hands the engine the system's *default input device*, and the person chooses it in
            // Sound settings (Bluetooth mics included, with no HFP negotiation for us to do). A reader
            // diffing against the iOS twin should stop looking for this block.

            // DIVERGENCE 2 — fail fast when there is no input device at all. iOS gets this for free from the
            // zero-format guard below, because an unavailable iOS route collapses `outputFormat(forBus: 0)`
            // to 0 Hz / 0 channels. On macOS it does NOT: with no mic attached the node still reports a
            // plausible default format, that guard passes, and the failure only surfaces later at
            // `engine.start()` — exactly the trap `MacDictation.isAvailable` documents (#120). Ask the
            // device directly instead, so the Kotlin seam gets its Failed state (→ the salvage path) rather
            // than a silently dead mic. Device enumeration needs no TCC; permission was requested by the
            // overlay before it asked us to record.
            guard AVCaptureDevice.default(for: .audio) != nil else {
                onFailed()
                return
            }

            let input = engine.inputNode
            // Apple's Voice Processing I/O: the OS DSP does mic-array noise suppression + echo cancellation,
            // stripping steady background (an AC hum, a fan) before we ever see the samples — no custom
            // filtering. AGC off so it denoises without pumping the recording's levels. Both APIs are
            // macOS 10.15+, well under this app's 14.0 floor, so they need no `@available` guard and port
            // unchanged; macOS simply has no user-visible "Voice Isolation" mic mode for them to engage,
            // which is a cosmetic difference, not a behavioural one. `try?`: if VPIO can't enable (device or
            // format won't take it), fall back to raw input rather than failing the take.
            try? input.setVoiceProcessingEnabled(true)
            input.isVoiceProcessingAGCEnabled = false
            // Read the format AFTER enabling VPIO — turning it on reconfigures the node.
            let format = input.outputFormat(forBus: 0)
            // Belt-and-braces after divergence 2: a device can exist and still hand back a degenerate format
            // (an aggregate device mid-reconfiguration, a device another process holds exclusively). Then
            // installing a tap — or opening an `AVAudioFile` — raises an AVFoundation NSException: an
            // uncatchable abort, not a Swift throw `try?` can swallow. Treat it as a failed start.
            // (iOS additionally deactivates its session in this arm; there is none here — divergence 1.)
            guard format.sampleRate > 0, format.channelCount > 0 else {
                onFailed()
                return
            }
            // A throwaway mic-rate → 16 kHz mono converter feeding ONLY the spectrum (display); the WAV below
            // keeps the mic's native rate. nil-safe — if it can't build, the bars just stay idle.
            converter = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: Self.spectrumRate,
                                      channels: 1, interleaved: false).flatMap { AVAudioConverter(from: format, to: $0) }
            // A standard PCM16 WAV at the mic's native rate/channels; AVAudioFile converts the float32 tap
            // buffers to 16-bit on write. (`MacFileTranscriber` resamples to the transcriber's format; the
            // recorder only needs a durable, retainable recording.)
            let settings: [String: Any] = [
                AVFormatIDKey: kAudioFormatLinearPCM,
                AVSampleRateKey: format.sampleRate,
                AVNumberOfChannelsKey: format.channelCount,
                AVLinearPCMBitDepthKey: 16,
                AVLinearPCMIsFloatKey: false,
                AVLinearPCMIsBigEndianKey: false,
            ]
            file = try? AVAudioFile(forWriting: URL(fileURLWithPath: filePath), settings: settings)

            // DIVERGENCE 3 — macOS-native hardening, the shape `MacDictation` already established. Wrap the
            // tap install (and `prepare`) in the Obj-C `@try/@catch` shim: `installTapOnBus` raises an
            // uncatchable `NSException` on a format/hardware mismatch, and Kotlin/Native's terminate handler
            // turns any exception that reaches it into `abort()` (ExceptionCatcher.h — the ADR-0029 Phase-2
            // crash). iOS can lean on its zero-format guard alone because an unavailable route zeroes the
            // format there; macOS cannot (divergence 2), so this shim is the real guard on this platform.
            // The block is `NS_NOESCAPE`, so a `return` cannot cross it — hence do/catch around the call
            // rather than an early return inside the closure.
            do {
                try DFNExceptionCatcher.catchException {
                    input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
                        guard let self else { return }
                        try? self.file?.write(from: buffer)
                        self.publishLevel(buffer)
                    }
                    engine.prepare()
                }
            } catch {
                file = nil // the tap never installed — leave no half-open file behind it
                // …and remove it anyway. The raise can come from EITHER statement in the block, so the tap
                // may in fact be installed (only `prepare()` blew up). `running` stays false, so `stop()`
                // takes its early-return arm and would never remove it — and a second `installTap` on an
                // already-tapped bus raises again. That turns one transient failure into a mic that is dead
                // until relaunch. `removeTap` on an un-tapped bus is a documented no-op, so this is safe in
                // both directions. (iOS reaches this arm only via its zero-format guard, before any tap
                // exists, so its twin has nothing to undo here.)
                input.removeTap(onBus: 0)
                onFailed()
                return
            }
            running = (try? engine.start()) != nil
            if !running {
                file = nil // engine didn't open — leave no half-open file
                input.removeTap(onBus: 0)
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
            // DIVERGENCE 1 again: iOS deactivates its `AVAudioSession` here to hand the route back to the
            // system. There is no session on macOS, so there is nothing to hand back — CoreAudio releases
            // the input device when the engine stops.
            running = false
            levels = Array(repeating: 0.05, count: Self.barCount)
            converter = nil
            ring = [Float](repeating: 0, count: Self.window)
        }
        if Thread.isMainThread { teardown() } else { DispatchQueue.main.sync(execute: teardown) }
    }

    /// Downsample the buffer to 16 kHz mono and hand a rolling ~64 ms window to the shared `AudioSpectrum` (the
    /// same direct-DFT-per-bar + dB-window code Android and iOS run), then publish the resulting `0…1` levels.
    /// Display only — the WAV keeps the mic's native rate. The 16 kHz / 1024 constants are not arbitrary: they
    /// are `AudioSpectrum.SAMPLE_RATE` and its `WINDOW`, so the Swift side must feed exactly that shape.
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
        // Deliberately NOT reimplemented in Swift — one implementation, three platforms.
        let samples = KotlinFloatArray(size: Int32(w))
        for i in 0..<w { samples.set(index: Int32(i), value: ring[i]) }
        let mags = AudioSpectrum.shared.magnitudes(samples: samples, bands: Int32(Self.barCount))
        var bars = [CGFloat](repeating: 0, count: Self.barCount)
        for i in 0..<Self.barCount { bars[i] = CGFloat(mags.get(index: Int32(i))) }

        DispatchQueue.main.async { [weak self] in self?.levels = bars }
    }
}

import AVFoundation
import Deferno
import SwiftUI

/// The **Brain dump** recorder overlay (ADR-0027) — the iOS twin of Android's `BrainDumpScreen`: a calm
/// voice recorder with a **live audio spectrum** reacting to the mic and an m:ss timer. It is a thin
/// render of the shared `BrainDumpComponent` state machine (Idle → Recording → Enqueued); the View owns
/// the iOS mic permission prompt and the `AVAudioEngine` meter that drives the spectrum.
///
/// Scope note (ponytail): the spectrum + capture are real and native; the transcription→draft pipeline
/// (SFSpeechRecognizer → Inbox drafts) rides the shared `recordBrainDump` seam, which is not yet wired on
/// iOS (DefernoRoot) — so a take currently records + enqueues without yet producing drafts. The upgrade
/// path is to implement that seam; this overlay needs no change when it lands.
struct BrainDumpView: View {
    let component: BrainDumpComponent
    @StateObject private var state: StateFlowObserver<BrainDumpState>
    @StateObject private var meter = AudioMeter()
    @Environment(\.defernoColors) private var colors
    @State private var elapsed: Int = 0
    @State private var timer: Timer?

    init(component: BrainDumpComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.brainDumpStateBridge(component: component)))
    }

    private var phase: String { ShellBridgeKt.brainDumpPhaseName(state: state.value) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text("Brain dump").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Close") { stopMeter(); component.dismiss() }
            }
            .padding(.horizontal, Layout.gutter).frame(minHeight: 56)

            Spacer()
            content
            Spacer()
        }
        .background(colors.background.ignoresSafeArea())
        .onChange(of: phase) { newPhase in
            if newPhase == "Recording" { startMeter() } else { stopMeter() }
        }
        .onDisappear { stopMeter() }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case "Recording":
            VStack(spacing: 28) {
                SpectrumBars(levels: meter.levels, color: colors.primary)
                    .frame(height: 96).padding(.horizontal, 32)
                MonoMeta(text: timeLabel(elapsed))
                stopButton
                Text("Listening…").font(.subheadline).foregroundStyle(colors.inkMuted)
            }
        case "Enqueued":
            VStack(spacing: 16) {
                DefernoIcon.check.image(size: 40).foregroundStyle(colors.success)
                Text("Sorting to your Inbox").font(.headline).foregroundStyle(colors.onSurface)
                Text("We'll transcribe this in the background and drop the results in your Inbox.")
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center).padding(.horizontal, 32)
                PrimaryActionButton(title: "Done", icon: .check) { component.dismiss() }
                    .padding(.horizontal, 48)
            }
        case "Failed":
            VStack(spacing: 16) {
                Text("Couldn't record").font(.headline).foregroundStyle(colors.onSurface)
                Text("Something went wrong reaching the microphone. Try again.")
                    .font(.subheadline).foregroundStyle(colors.inkMuted).multilineTextAlignment(.center)
                micButton
            }.padding(.horizontal, 32)
        case "PermissionDenied", "PermissionPermanentlyDenied":
            VStack(spacing: 16) {
                DefernoIcon.mic.image(size: 36).foregroundStyle(colors.inkMuted)
                Text("Needs microphone access").font(.headline).foregroundStyle(colors.onSurface)
                Text("Allow Deferno to use the microphone to capture a brain dump by voice.")
                    .font(.subheadline).foregroundStyle(colors.inkMuted).multilineTextAlignment(.center)
                if phase == "PermissionPermanentlyDenied" {
                    PrimaryActionButton(title: "Open Settings") { component.openDictationPermissionSettings() }
                        .padding(.horizontal, 48)
                } else {
                    micButton
                }
            }.padding(.horizontal, 32)
        default: // Idle
            VStack(spacing: 20) {
                Text("Speak your mind").font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
                Text("Tap to record. We'll sort what you say into your Inbox.")
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center).padding(.horizontal, 40)
                micButton
            }
        }
    }

    private var micButton: some View {
        Button(action: requestThenRecord) {
            ZStack {
                Circle().fill(colors.primary)
                DefernoIcon.mic.image(size: 36).foregroundStyle(colors.onPrimary)
            }
            .frame(width: 96, height: 96)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Record")
    }

    private var stopButton: some View {
        Button { component.stopRecording() } label: {
            ZStack {
                Circle().fill(colors.error)
                RoundedRectangle(cornerRadius: 4).fill(colors.onError).frame(width: 26, height: 26)
            }
            .frame(width: 80, height: 80)
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Stop recording")
    }

    private func requestThenRecord() {
        let handle: (Bool) -> Void = { granted in
            DispatchQueue.main.async {
                if granted { component.startRecording() }
                else { component.dictationPermissionDenied(permanentlyDenied: false) }
            }
        }
        // AVAudioApplication is the iOS-17 home for record-permission; fall back to the (now-deprecated)
        // AVAudioSession call on iOS 16 (the deployment target).
        if #available(iOS 17.0, *) {
            AVAudioApplication.requestRecordPermission(completionHandler: handle)
        } else {
            AVAudioSession.sharedInstance().requestRecordPermission(handle)
        }
    }

    // MARK: meter + timer

    private func startMeter() {
        meter.start()
        elapsed = 0
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in elapsed += 1 }
    }

    private func stopMeter() {
        meter.stop()
        timer?.invalidate(); timer = nil
    }

    private func timeLabel(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// A live VU spectrum (iOS twin of `SpectrumBars.kt`): a row of capsules whose heights track a rolling
/// window of mic levels, newest on the right.
struct SpectrumBars: View {
    let levels: [CGFloat]
    let color: Color

    var body: some View {
        GeometryReader { geo in
            HStack(alignment: .center, spacing: 4) {
                ForEach(levels.indices, id: \.self) { i in
                    Capsule()
                        .fill(color.opacity(0.55 + 0.45 * levels[i]))
                        .frame(height: max(4, levels[i] * geo.size.height))
                        .frame(maxHeight: .infinity, alignment: .center)
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .animation(.easeOut(duration: 0.12), value: levels)
        }
        .accessibilityHidden(true)
    }
}

/// An `AVAudioEngine` mic meter that publishes a rolling window of RMS levels (0…1) for `SpectrumBars`.
final class AudioMeter: ObservableObject {
    @Published var levels: [CGFloat] = Array(repeating: 0.05, count: 28)
    private let engine = AVAudioEngine()
    private var running = false

    func start() {
        guard !running else { return }
        let session = AVAudioSession.sharedInstance()
        try? session.setCategory(.record, mode: .measurement, options: [])
        try? session.setActive(true, options: [])
        let input = engine.inputNode
        let format = input.outputFormat(forBus: 0)
        input.installTap(onBus: 0, bufferSize: 1024, format: format) { [weak self] buffer, _ in
            guard let self, let channel = buffer.floatChannelData?[0] else { return }
            let count = Int(buffer.frameLength)
            guard count > 0 else { return }
            var sum: Float = 0
            for i in 0..<count { let s = channel[i]; sum += s * s }
            let rms = sqrt(sum / Float(count))
            // Map RMS to a lively 0…1 bar height (mic input is quiet; scale + clamp).
            let level = CGFloat(min(1, max(0.05, CGFloat(rms) * 14)))
            DispatchQueue.main.async {
                var next = self.levels
                next.removeFirst()
                next.append(level)
                self.levels = next
            }
        }
        engine.prepare()
        running = (try? engine.start()) != nil
    }

    func stop() {
        guard running else { return }
        engine.inputNode.removeTap(onBus: 0)
        engine.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: [])
        running = false
        levels = Array(repeating: 0.05, count: 28)
    }

    deinit { stop() }
}

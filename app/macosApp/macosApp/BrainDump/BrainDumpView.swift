import Deferno
// SidecarKit is imported here ONLY for `SidecarPermissions` (the app's one TCC broker). This file must
// never name `SpeechTranscriber` — SidecarKit vends a class by that name, and so does macOS 26's Speech
// framework. The transcription half of the feature lives in `MacFileTranscriber.swift`, which deliberately
// does NOT import SidecarKit; that split is what keeps the collision impossible in both files.
import SidecarKit
import SwiftUI

/// The **Brain dump** recorder overlay (ADR-0027) — the macOS twin of iosApp's `BrainDumpView` and
/// Android's `BrainDumpScreen`: a calm voice recorder with a **live audio spectrum** reacting to the mic and
/// an m:ss timer. It is a thin render of the shared `BrainDumpComponent` state machine
/// (Idle → Recording → Enqueued); the View owns the macOS TCC prompt, and nothing else — the mic itself
/// belongs to the injected `MacBrainDumpRecorder`, the single engine owner, so there is no second tap
/// contending for the input device.
///
/// The capture, spectrum AND on-device pipeline are real (#267/#269): the shared `recordBrainDump` seam
/// (`DefernoRoot`) records the mic to a durable WAV and, on Stop, hands the take to the shared
/// `BrainDumpPipeline` — on-device transcription through `MacFileTranscriber`, or a Salvage draft in the
/// Inbox when it yields nothing (ADR-0037: a recorded take is never thrown away).
///
/// macOS presents this as a **sheet on a window** rather than a full-screen phone overlay, which is the one
/// structural divergence from the iOS twin; see `body`.
struct BrainDumpView: View {
    let component: BrainDumpComponent
    @StateObject private var state: StateFlowObserver<BrainDumpState>
    /// The shared recorder the Kotlin seam drives; the View only observes its `levels` for the spectrum.
    @ObservedObject var recorder: MacBrainDumpRecorder
    @Environment(\.defernoColors) private var colors
    @State private var elapsed: Int = 0
    @State private var timer: Timer?

    init(component: BrainDumpComponent, recorder: MacBrainDumpRecorder) {
        self.component = component
        self.recorder = recorder
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    private var phase: String { ShellBridgeKt.brainDumpPhaseName(state: state.value) }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L.string("braindump_title")).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(L.string("common_close")) { stopTimer(); component.dismiss() }
            }
            .padding(.horizontal, Layout.gutter).frame(minHeight: 56)

            Spacer()
            content
            Spacer()
        }
        // A FLOOR, not a fixed size. macOS presents this as a sheet on a window, so — unlike the phone —
        // there is no screen to fill. `DraftExtractorView` pins a hard 460×480 because its content is a
        // fixed form; the recorder's content is short and centred, so a minimum lets the sheet breathe
        // while never collapsing to a sliver (which the Search/New natural-size convention would allow for
        // content this sparse).
        //
        // The `ideal*` pair is load-bearing, not decoration: a sheet proposes `nil`, and a flex frame with
        // only a *minimum* then proposes `nil` on down, so the `Spacer()`s above and below `content` stay
        // at their ideal (zero) length. The frame would still report 420×380 — but the VStack inside it
        // would be a small block centred in that box, floating the header away from the top edge. Giving
        // the frame an ideal makes it propose 420×380 to the VStack, so the Spacers expand and the header
        // pins where it belongs. `BreakdownView` solves the same problem the other way (a greedy
        // `.frame(maxWidth:.infinity, maxHeight:.infinity)` on its content).
        .frame(minWidth: 420, idealWidth: 420, minHeight: 380, idealHeight: 380)
        // `.background` sits OUTSIDE the frame — same order as `BreakdownView`, and for the same reason:
        // inside it, the fill would only ever cover the VStack's own size, leaving the sheet's default
        // material showing wherever the floor is larger. No `.ignoresSafeArea()` (iOS has it): a Mac sheet
        // has no safe area to ignore, and the modifier would be inert noise here.
        .background(colors.background)
        // The macOS-14 two-parameter form. iOS's single-closure `onChange(of:)` is deprecated at this
        // deployment target and would break the zero-Swift-warning gate; every other macOS View here
        // already uses this shape (`NewItemView`, `ItemTreeView`).
        .onChange(of: phase) { _, newPhase in
            if newPhase == "Recording" { startTimer() } else { stopTimer() }
        }
        .onDisappear { stopTimer() }
    }

    @ViewBuilder
    private var content: some View {
        switch phase {
        case "Recording":
            VStack(spacing: 28) {
                SpectrumBars(levels: recorder.levels)
                    // Desktop density — the one deliberate size divergence from iOS's 50×30. Sixteen bars in
                    // 50pt is ~2pt each: legible on a phone held at arm's length, a speck on a Mac at desk
                    // distance. The atom itself is untouched; only this call site widens.
                    .frame(width: 220, height: 44)
                MonoMeta(timeLabel(elapsed))
                stopButton
                Text(L.string("braindump_listening")).font(.subheadline).foregroundStyle(colors.inkMuted)
            }
        case "Enqueued":
            VStack(spacing: 16) {
                DefernoIcon.check.image(size: 40).foregroundStyle(colors.success)
                Text(L.string("braindump_sorting_title")).font(.headline).foregroundStyle(colors.onSurface)
                Text(L.string("braindump_sorting_body"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center).padding(.horizontal, 32)
                PrimaryActionButton(title: L.string("calendar_action_done"), icon: .check) { component.dismiss() }
                    .padding(.horizontal, 48)
            }
        case "Failed":
            VStack(spacing: 16) {
                Text(L.string("braindump_error_title")).font(.headline).foregroundStyle(colors.onSurface)
                Text(L.string("braindump_error_body"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted).multilineTextAlignment(.center)
                micButton
            }.padding(.horizontal, 32)
        case "PermissionDenied", "PermissionPermanentlyDenied":
            VStack(spacing: 16) {
                DefernoIcon.mic.image(size: 36).foregroundStyle(colors.inkMuted)
                Text(L.string("braindump_mic_needed_title")).font(.headline).foregroundStyle(colors.onSurface)
                Text(L.string("braindump_mic_needed_body"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted).multilineTextAlignment(.center)
                if phase == "PermissionPermanentlyDenied" {
                    PrimaryActionButton(title: L.string("common_open_settings")) { component.openDictationPermissionSettings() }
                        .padding(.horizontal, 48)
                } else {
                    micButton
                }
            }.padding(.horizontal, 32)
        default: // Idle
            VStack(spacing: 20) {
                Text(L.string("braindump_idle_title")).font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
                Text(L.string("braindump_idle_body"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted)
                    .multilineTextAlignment(.center).padding(.horizontal, 40)
                micButton
            }
        }
    }

    // The two hero discs keep the iOS diameters (96 / 80) rather than dropping to `Layout.minTouchTarget`.
    // That token is the floor for a *row* control in a dense desktop list; these are the single subject of
    // the sheet, and a Record button the size of a checkbox would read as an afterthought. Deliberate — not
    // an un-ported desktop-density pass.

    private var micButton: some View {
        Button(action: requestThenRecord) {
            ZStack {
                Circle().fill(colors.primary)
                DefernoIcon.mic.image(size: 36).foregroundStyle(colors.onPrimary)
            }
            .frame(width: 96, height: 96)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L.string("braindump_record_a11y"))
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
        .accessibilityLabel(L.string("braindump_stop_recording_a11y"))
    }

    /// Request TCC, then record. Fully rewritten for macOS — iOS's `AVAudioApplication` /
    /// `AVAudioSession.requestRecordPermission` branch is gone. Three decisions worth recording:
    ///
    /// 1. **Why `SidecarPermissions` and not `AVAudioApplication`.**
    ///    `AVAudioApplication.requestRecordPermission` *is* available on macOS 14, so this is a deliberate
    ///    choice rather than a forced one: `SidecarPermissions` is the single TCC broker this app already
    ///    goes through (`MacDictation.ensureAuthorized`), and it reports a `PermissionStatusValue` instead
    ///    of a bare `Bool` — which is exactly what lets us tell a terminal `.denied` from `.notDetermined`
    ///    in (3).
    ///
    /// 2. **Why Speech is requested but does NOT gate.** The Speech leg mirrors
    ///    `MacDictation.ensureAuthorized`'s order so the prompt lands at the natural moment (the person just
    ///    asked to record) and authorization is already in hand if macOS 26's analyzer stack consults it.
    ///    Its result is deliberately discarded, though: this recorder only writes a plain WAV through
    ///    `AVAudioEngine`, so mic TCC is the only permission it genuinely needs; transcription happens
    ///    *later*, in `MacFileTranscriber`, over a finalized file. Letting a Speech denial block the
    ///    recording would break the never-waste-input invariant (ADR-0037) — the take would be lost rather
    ///    than salvaged as a draft. `MacDictation` gates on it because it *is* `SFSpeechRecognizer`, which
    ///    cannot run at all without Speech TCC.
    ///
    /// 3. **Why `permanentlyDenied` is computed here, and is `true` on a denial.** A macOS TCC denial is
    ///    terminal — the OS never re-prompts (see `MacDictation`) — so `.denied`/`.restricted` on the Mac IS
    ///    the shared `PermissionPermanentlyDenied` phase, and its "Open settings" arm is reachable. iOS
    ///    passes `false` unconditionally because iOS re-prompts. This is a platform-forced divergence, not a
    ///    preference.
    private func requestThenRecord() {
        SidecarPermissions.requestSpeech { _ in
            SidecarPermissions.requestMicrophone { mic in
                // The TCC completions fire on a background XPC queue; the component is main-thread-confined.
                DispatchQueue.main.async {
                    if mic == .granted {
                        component.startRecording()
                    } else {
                        component.dictationPermissionDenied(permanentlyDenied: mic == .denied || mic == .restricted)
                    }
                }
            }
        }
    }

    // MARK: elapsed timer (the mic + spectrum are owned by the injected MacBrainDumpRecorder)

    private func startTimer() {
        elapsed = 0
        timer?.invalidate()
        timer = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { _ in elapsed += 1 }
    }

    private func stopTimer() {
        timer?.invalidate(); timer = nil
    }

    private func timeLabel(_ seconds: Int) -> String {
        String(format: "%d:%02d", seconds / 60, seconds % 60)
    }
}

/// A live frequency spectrum (macOS twin of `SpectrumBars.kt` and the iOS `SpectrumBars`): a fixed row of
/// capsules whose heights track the mic's FFT bands — low frequencies on the left, high on the right. Each
/// capsule is also tinted by its level off a green→red ramp (green at rest, red at peak). Ported verbatim;
/// `CGFloat` and `Color(hue:saturation:brightness:)` are cross-platform, and the bar *count* is not
/// duplicated here — it is whatever `MacBrainDumpRecorder.levels` publishes.
struct SpectrumBars: View {
    let levels: [CGFloat]

    /// Pre-rendered 16-step green→red ramp, hue-interpolated (green 120° → red 0°) so the steps read as a smooth
    /// green→yellow→orange→red heat gradient. Brightness stays at 1.0 so the mid-ramp yellow never dims toward
    /// olive/brown; saturation is eased to soften the green end. Indexed by level: 0% → green, 100% → red.
    private static let palette: [Color] = (0..<16).map { i in
        let hue = (1 - Double(i) / 15) / 3 // 1/3 (green) → 0 (red)
        return Color(hue: hue, saturation: 0.8, brightness: 1.0)
    }

    private static func color(for level: CGFloat) -> Color {
        palette[min(15, max(0, Int((level * 15).rounded())))]
    }

    var body: some View {
        GeometryReader { geo in
            HStack(alignment: .center, spacing: 1) {
                ForEach(levels.indices, id: \.self) { i in
                    Capsule()
                        .fill(Self.color(for: levels[i]))
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

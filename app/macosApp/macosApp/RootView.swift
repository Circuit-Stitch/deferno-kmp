import Deferno
import SwiftUI

/// The app root — the SwiftUI twin of Android's `RootShell` + `DefernoTheme` (ADR-0013/0017). It
/// observes the shared `RootComponent`'s two-state Auth↔Main stack and the app-wide theme settings,
/// applies the live brand theme, and renders the **Auth shell** (browser-OAuth sign-in, ADR-0026) or
/// the **Main shell**. The Active Account drives the swap reactively — a successful sign-in flips it and the
/// surface changes out from under the sign-in screen (there is no success callback to wire).
struct RootView: View {
    let root: RootComponent
    /// The app-lifetime Brain dump mic owner (#368 Tranche 5b), threaded down to the Main shell's recorder
    /// overlay. Held here rather than created per-View so the Kotlin `recordBrainDump` seam and the
    /// spectrum observe the SAME `AVAudioEngine` — one mic, no contending taps (the iOS shape).
    @ObservedObject var recorder: MacBrainDumpRecorder
    @StateObject private var stack: StateFlowObserver<RootComponentChild>
    @StateObject private var theme: StateFlowObserver<UserSettings>

    init(root: RootComponent, recorder: MacBrainDumpRecorder) {
        self.root = root
        self.recorder = recorder
        _stack = StateObject(wrappedValue: StateFlowObserver(root.activeChild))
        _theme = StateObject(wrappedValue: StateFlowObserver(root.themeSettings))
    }

    var body: some View {
        content
            .defernoTheme(theme.value)
    }

    @ViewBuilder
    private var content: some View {
        let child = stack.value
        if let main = ShellBridgeKt.rootChildMain(child: child) {
            MainShellView(component: main, recorder: recorder)
        } else if let auth = ShellBridgeKt.rootChildAuth(child: child) {
            // `onCancel` is non-nil only when re-entered to add an account (#368) — it shows a Cancel-back.
            // Without it the Settings roster's "Add another account" would strand the user here: macOS has
            // no system back gesture, so the Auth shell would have no exit at all.
            SignInView(component: auth.signIn, onCancel: auth.onCancel)
        }
    }
}

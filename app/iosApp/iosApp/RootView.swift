import Deferno
import SwiftUI

/// The app root — the SwiftUI twin of Android's `RootShell` + `DefernoTheme` (ADR-0013/0017). It
/// observes the shared `RootComponent`'s two-state Auth↔Main stack and the app-wide theme settings,
/// applies the live brand theme, and renders the **Auth shell** (browser-OAuth sign-in, ADR-0026) or
/// the **Main shell**. The Active Account drives the swap reactively — a successful sign-in flips it and the
/// surface changes out from under the sign-in screen (there is no success callback to wire).
struct RootView: View {
    let root: RootComponent
    /// Threaded down to the Brain dump overlay's spectrum (#267) — RootView only forwards it.
    let recorder: BrainDumpRecorder
    @StateObject private var stack: StateFlowObserver<RootComponentChild>
    @StateObject private var theme: StateFlowObserver<UserSettings>

    init(root: RootComponent, recorder: BrainDumpRecorder) {
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
            SignInView(component: auth.signIn)
        }
    }
}

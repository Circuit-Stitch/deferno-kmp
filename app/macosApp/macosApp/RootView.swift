import Deferno
import SwiftUI

/// The app root — the SwiftUI twin of Android's `RootShell` + `DefernoTheme` (ADR-0013/0017). It
/// observes the shared `RootComponent`'s two-state Auth↔Main stack and the app-wide theme settings,
/// applies the live brand theme, and renders the **Auth shell** (browser-OAuth sign-in, ADR-0026) or
/// the **Main shell**. The Active Account drives the swap reactively — a successful sign-in flips it and the
/// surface changes out from under the sign-in screen (there is no success callback to wire).
struct RootView: View {
    let root: RootComponent
    let onBrainDump: () -> Void
    @StateObject private var stack: StateFlowObserver<RootComponentChild>
    @StateObject private var theme: StateFlowObserver<UserSettings>

    init(root: RootComponent, onBrainDump: @escaping () -> Void) {
        self.root = root
        self.onBrainDump = onBrainDump
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
            MainShellView(component: main, onBrainDump: onBrainDump)
        } else if let auth = ShellBridgeKt.rootChildAuth(child: child) {
            // `onCancel` is non-nil only when re-entered to add an account (#368) — it shows a Cancel-back.
            // Without it the Settings roster's "Add another account" would strand the user here: macOS has
            // no system back gesture, so the Auth shell would have no exit at all.
            SignInView(component: auth.signIn, onCancel: auth.onCancel)
        }
    }
}

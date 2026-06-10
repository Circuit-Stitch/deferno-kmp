import Deferno
import SwiftUI

/// The app root — the SwiftUI twin of Android's `RootShell` + `DefernoTheme` (ADR-0013/0017). It
/// observes the shared `RootComponent`'s two-state Auth↔Main stack and the app-wide theme settings,
/// applies the live brand theme, and renders the **Auth shell** (browser-OAuth sign-in, ADR-0026) or
/// the **Main shell**. The Active Account drives the swap reactively — a successful sign-in flips it and the
/// surface changes out from under the sign-in screen (there is no success callback to wire).
struct RootView: View {
    let root: RootComponent
    @StateObject private var stack: RootStackObserver
    @StateObject private var theme: StateFlowObserver<UserSettings>

    init(root: RootComponent) {
        self.root = root
        _stack = StateObject(wrappedValue: RootStackObserver(ShellBridgeKt.rootStackBridge(component: root)))
        _theme = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.themeSettingsBridge(component: root)))
    }

    var body: some View {
        content
            .defernoTheme(theme.value)
    }

    @ViewBuilder
    private var content: some View {
        let child = stack.active
        if let main = ShellBridgeKt.rootChildMain(child: child) {
            MainShellView(component: main)
        } else if let auth = ShellBridgeKt.rootChildAuth(child: child) {
            SignInView(component: auth.signIn)
        }
    }
}

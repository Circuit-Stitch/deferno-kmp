// AppKit for `NSApp` only (activate / terminate) — the two things SwiftUI has no equivalent for when a
// menu-bar row or a global hotkey fires from *outside* the View tree (#368 G26). Deliberately NOT
// `import SidecarKit`: that module vends a `SpeechTranscriber` which collides silently with macOS 26's
// Speech framework (see MacFileTranscriber.swift:4-12), so the SidecarKit dependency stays confined to
// MenuBarController.swift next door, whose own surface is SidecarKit-free.
import AppKit
import Deferno
import SwiftUI

/// Routes the menu-bar rows and the global ⌘⇧D hotkey into the running app (#368 G26) — deliberately the
/// only place the menu bar meets the shared shell and AppKit, so `MenuBarController` can stay a pure,
/// AppKit-free description of the menu that `MenuBarControllerTests` asserts without a window server.
///
/// Every command fires from **outside** the SwiftUI View tree: an `NSStatusItem` menu row, or a Carbon
/// hotkey press that can arrive while Deferno is hidden behind whatever the person was actually doing.
/// That drives both decisions here — the shell is reached through the root (`ShellBridgeKt`, the same seam
/// the ⌘N command uses), and anything that shows UI has to front the app itself first.
final class AppMenuBarCommands: MenuBarCommands {

    private let host: DefernoRoot

    /// SwiftUI's `openWindow(id:)` for the `main` scene, captured by `MainWindowBinder` (below) from inside
    /// the window's own body — see there for why it cannot be read off the `App` struct.
    ///
    /// The no-op default degrades correctly rather than failing: `NSApp.activate()` on its own already
    /// fronts an *open* window, so the only case this hook adds is a window that no longer exists — and
    /// by then the binder has long since run, since the `main` scene opens at launch.
    var reopenMainWindow: () -> Void = {}

    init(host: DefernoRoot) {
        self.host = host
    }

    /// ⌘⇧D, and the status item's "Brain dump" row: front the app, *then* open the capture overlay on the
    /// foreground shell. The order is the whole point — an overlay opened in a background (or windowless)
    /// app is invisible, and a hotkey exists to catch a thought without first going to find the window.
    ///
    /// Signed out this is a no-op by design (`openBrainDumpOnActiveShell` returns early when the Auth shell
    /// is foreground) — the app still comes forward, which is the honest outcome: it shows you the sign-in
    /// you have to deal with instead of silently swallowing the press.
    func openBrainDump() {
        showMainWindow()
        ShellBridgeKt.openBrainDumpOnActiveShell(root: host.root)
    }

    /// Bring Deferno forward. Two independent problems, so two calls: `activate()` fronts the *app* (the
    /// hotkey can fire while another app is frontmost), and `reopenMainWindow()` re-creates the `main`
    /// scene's window if the person closed it — activation cannot bring back a window that does not exist,
    /// and `main` is a `Window` rather than a `WindowGroup`, so closing it leaves the app alive and
    /// windowless. `openWindow` on an already-open window just brings it to the front, so running the pair
    /// unconditionally is both correct and cheaper than trying to detect which case we are in.
    ///
    /// `activate()`, not `activate(ignoringOtherApps:)`: the latter is deprecated as of macOS 14, which is
    /// this app's deployment target, and CI builds with SWIFT_TREAT_WARNINGS_AS_ERRORS=YES.
    func showMainWindow() {
        NSApp.activate()
        reopenMainWindow()
    }

    /// The menu's last row. `NSApp.terminate` is the same path ⌘Q takes, so a status-item quit and an
    /// App-menu quit are one behaviour (SwiftUI's termination handling included), not two.
    func quit() {
        NSApp.terminate(nil)
    }
}

/// Installs the menu bar from *inside* the main window's View tree, and hands its commands a live
/// `openWindow` action (#368 G26). Applied by `DefernoApp`'s `main` scene.
///
/// Both jobs are here for the same reason. `@Environment` only resolves while a View body is being
/// evaluated: read off the `App` struct it silently returns the **default** `OpenWindowAction` — a no-op —
/// and logs "Accessing Environment's value outside of being installed on a View". The menu rows and the
/// hotkey fire from AppKit callbacks, long outside any body, so the action is resolved once here and the
/// resolved *value* is captured. That value keeps working after this view goes away, which is precisely
/// the case that matters: reopening `main` after the person has closed it.
///
/// `.onAppear` also runs after `NSApplication.run`, which is what `MenuBarController.install()` wants (a
/// status item needs `NSStatusBar.system`, a Carbon hotkey needs the event dispatcher). It re-fires when a
/// closed window is reopened; `install()` is idempotent for exactly that reason.
struct MainWindowBinder: ViewModifier {

    @Environment(\.openWindow) private var openWindow
    let commands: AppMenuBarCommands
    let controller: MenuBarController

    func body(content: Content) -> some View {
        content.onAppear {
            // Resolve now, while the environment is installed, and capture the resolved action — NOT
            // `self`, which would re-read `@Environment` at fire time and get the no-op default.
            let open = openWindow
            commands.reopenMainWindow = { open(id: DefernoApp.mainWindowId) }
            controller.install()
        }
    }
}

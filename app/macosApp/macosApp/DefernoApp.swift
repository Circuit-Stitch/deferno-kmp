import Deferno
import SwiftUI
import UserNotifications

/// macOS app entry (ADR-0029, Phase 1b). Owns the shared component tree for the app's lifetime and
/// hands its `RootComponent` to SwiftUI. That tree is the **real** shared shell over the DI graph
/// (`DefernoRoot` — the macOS analogue of `DefernoApplication` + `MainActivity`), not the in-memory
/// `DefernoDemoRoot` scaffold: the Views render `RootComponent → Auth/Main → the Destination graph`
/// (ADR-0013/0017). The app opens on the Auth shell; a pasted staging PAT flips the Active Account and
/// the Main shell renders over the real data layer. Bridged by the hand-written SKIE-free bridge until
/// SKIE supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    // The Brain dump mic recorder (#368 Tranche 5b) is shared: the Kotlin host drives it (record → the
    // on-device pipeline), and the Brain dump overlay observes its `levels` for the spectrum — one engine,
    // no second mic tap. Created before `host` because `host` takes it.
    @StateObject private var recorder: MacBrainDumpRecorder
    @State private var host: DefernoRoot
    // Retained because `UNUserNotificationCenter.delegate` is weak (#271).
    @State private var notificationDelegate: BrainDumpNotificationDelegate
    // The menu-bar status item + the global ⌘⇧D Brain dump hotkey (#368 G26, ADR-0029 decision 2 — linked
    // in-process, no launchd Helper and no socket). Both live in MenuBar/; the app's only job is to own
    // them for its lifetime. Retained for the same reason `notificationDelegate` above is, only more so:
    // the status item's `NSMenuItem`s target the controller and AppKit holds those targets weakly — drop
    // it and the flame stays in the menu bar with every row silently doing nothing. `commands` is held
    // separately because `MainWindowBinder` has to hand it a live `openWindow` once the View tree exists.
    @State private var menuBarCommands: AppMenuBarCommands
    @State private var menuBar: MenuBarController
    @State private var showExtractor = false

    /// The `main` scene's window id. Named once because it is used twice: by the scene itself and by
    /// `openWindow(id:)` when the menu bar has to bring back a window the person closed (see
    /// `MainWindowBinder`) — two string literals that must agree, with nothing to catch them drifting.
    static let mainWindowId = "main"

    init() {
        let recorder = MacBrainDumpRecorder()
        _recorder = StateObject(wrappedValue: recorder)
        // The backend environment is INJECTED per Xcode build configuration (ADR-0047, #368 G22): the
        // per-config `DEFERNO_ENV` build setting (project.yml) surfaces through Info.plist as `DefernoEnv`;
        // the Kotlin mapper turns that string into the `DefernoEnvironment` enum, decoupling the env from
        // `Platform.isDebugBinary` — so ProdDebug is a debuggable build that talks to Production.
        let envName = Bundle.main.object(forInfoDictionaryKey: "DefernoEnv") as? String
        // Phase 2 (ADR-0029): the in-process dictation engine (SidecarKit `SpeechTranscriber`, on-device).
        // Phase 3 (ADR-0029): the in-process inference engine (Foundation Models, on-device). Both run under
        // this app's own identity (no Helper); the inference engine drives `host.draftTasks` (the Extractor)
        // AND — since #368 Tranche 5 — the Brain dump Extractor that turns a transcript into real drafts.
        let host = DefernoRoot(
            recorder: recorder,
            dictation: MacDictation(),
            inference: MacInference(),
            // The on-device whole-file transcriber the Brain dump pipeline runs over the finalized WAV
            // (macOS 26 `SpeechAnalyzer`; older Macs report unavailable and the take salvages).
            fileTranscriber: MacFileTranscriber(),
            // The server-mediated Assistant SSE turn-stream (#282, ADR-0040): a raw URLSession reader Kotlin
            // drives with the Active-Account PAT. The entitled-gated Assistant Destination stays absent until
            // the Org is entitled, so this is inert for a non-entitled account.
            transport: MacAssistantTransport(),
            environment: DefernoRootKt.defernoEnvironment(name: envName)
        )
        _host = State(initialValue: host)
        // Brain dump completion notification (#271): a tap routes to the Inbox through the shared shell.
        let delegate = BrainDumpNotificationDelegate()
        delegate.onOpenInbox = { host.forwardOpenInbox() }
        _notificationDelegate = State(initialValue: delegate)
        UNUserNotificationCenter.current().delegate = delegate
        // The menu bar (#368 G26): built here, like the notification delegate, closing over the same
        // `host`. `install()` is deliberately NOT called here — it is deferred to the main window's
        // `.onAppear` (below), because this initialiser runs *before* `NSApplication.run`, and both halves
        // want a fully launched app: `NSStatusBar.system` to place the item, and the Carbon event
        // dispatcher to carry hotkey presses.
        let menuBarCommands = AppMenuBarCommands(host: host)
        _menuBarCommands = State(initialValue: menuBarCommands)
        _menuBar = State(initialValue: MenuBarController(commands: menuBarCommands))
    }

    var body: some Scene {
        // A single `Window` (not a `WindowGroup`): Deferno is a one-window app, and the OAuth redirect
        // re-entering via the custom scheme must NOT spawn a second window — it has to land on the live
        // shell so the in-flight sign-in's inbox receives it (#189). The explicit title also names the
        // window "Deferno" regardless of the bundle name.
        Window(L.string("common_app_name"), id: Self.mainWindowId) {
            RootView(root: host.root, recorder: recorder)
                // No global minWidth (#194): the window's floor is *dynamic* — it tracks the panes
                // currently open (sidebar + list + detail each carry their own `minWidth`, summed by
                // `.windowResizability(.contentMinSize)` below). Collapse the sidebar / deselect the
                // task and the window shrinks to just the list. A modest height floor keeps a pane
                // header + a row visible.
                .frame(minHeight: 360)
                // OAuth redirect (ADR-0026, #137): the system browser returns to the registered
                // `com.circuitstitch.deferno` scheme (project.yml URL types); forward it to the shared
                // inbox the in-flight `MacBrowserAuthenticator` awaits. On macOS this is the PRIMARY
                // capture path (#189), not just a fallback.
                .onOpenURL { url in
                    host.forwardAuthRedirect(url: url.absoluteString)
                }
                // Themed from the ROOT, not from `RootView`'s environment: this modifier is attached to
                // the `RootView(...)` value from outside, while `.defernoTheme` is applied *inside*
                // `RootView`'s own body — so a sheet presented here would resolve `\.defernoColors` to
                // the `EnvironmentKey` default (`defernoLight`) no matter what the person picked. Same
                // shape as the detached `task-detail` scene (ItemDetailWindowView), which observes
                // `root.themeSettings` for the same reason (#368 G18).
                .sheet(isPresented: $showExtractor) {
                    ThemedSheet(root: host.root) { DraftExtractorView(bridge: host.draftTasks) }
                }
                // Menu bar + global hotkey (#368 G26). Attached to the main window's content because both
                // of the things it does need a running app and a live View tree — see MainWindowBinder.
                .modifier(MainWindowBinder(commands: menuBarCommands, controller: menuBar))
        }
        // Honour the content's min frame as the window's minimum size (the window still resizes up).
        .windowResizability(.contentMinSize)

        // Detached, navigable per-item detail windows (#196, ADR-0033): a SECOND scene, opened by a tree
        // row's double-click / "Open in New Window" via `openWindow(id:value:)`. It coexists with the main
        // window's inline pane and NEVER handles auth — the `main` Window stays the sole owner of
        // `onOpenURL` + sign-in (#189). Value-based, so opening an already-open item brings its existing
        // window to the front (native dedupe) rather than duplicating it.
        //
        // **The payload is the KIND-CARRYING token** `"<Kind>:<raw id>"` (`BridgeKt.itemDetailToken`), not
        // the bare id it used to be (#383). A `WindowGroup` value has to be a single `Codable` — and the
        // dedupe above is *value* equality — so the pair travels as one string; `openItemDetailWindow`
        // decodes it back to an `ItemRef` and picks the detail the kind actually has. The scene id stays
        // "task-detail": macOS restores windows by scene id across launches, and a payload written by an
        // older build decodes correctly (see `BridgeKt.itemRefFromToken`), so renaming it would orphan
        // those restorations to buy nothing.
        WindowGroup(id: "task-detail", for: String.self) { $token in
            if let token {
                ItemDetailWindowView(host: host, token: token)
            }
        }
        .windowResizability(.contentMinSize)
        .defaultSize(width: 480, height: 620)

        .commands {
            // ⌘N opens the New-task overlay on the foreground Destination (pre-dated on Calendar, #74) —
            // the standard File → New slot, routed through the root since commands fire outside the View.
            CommandGroup(replacing: .newItem) {
                Button(L.string("shell_drawer_new_task")) { ShellBridgeKt.openNewOnActiveShell(root: host.root) }
                    .keyboardShortcut("n", modifiers: .command)
            }
            // Refresh the foreground Destination (⌘R) — the menu home for what used to be each pane's
            // "Refresh" button (the desktop twin of the Compose menu bar's View → Refresh).
            CommandMenu(L.string("shell_menu_view")) {
                Button(L.string("common_refresh")) { ShellBridgeKt.refreshActiveDestination(root: host.root) }
                    .keyboardShortcut("r", modifiers: .command)
            }
            // The Phase-3 demo trigger lives in a menu (⌘⇧E), not the shared shell — it's a macOS-app
            // dev surface for exercising the on-device Extractor, not a shipped product flow yet. Until
            // #368 Tranche 5 the title bar's Brain dump action *also* opened it, because macOS had no
            // recorder to open instead; now that it does, this menu item is the sheet's only entry point.
            CommandMenu(L.string("draft_extract_menu_section")) {
                Button(L.string("draft_extract_menu_item")) { showExtractor = true }
                    .keyboardShortcut("e", modifiers: [.command, .shift])
            }
        }
    }
}

/// Routes a tapped Brain dump completion notification (#271) to the Inbox — the macOS twin of iOS's
/// delegate. `UNUserNotificationCenter.delegate` is weak, so `DefernoApp` retains this; `onOpenInbox` is
/// wired to the host's `forwardOpenInbox()`, which switches the shell to the Inbox now (or defers if the
/// Auth shell is up).
final class BrainDumpNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    var onOpenInbox: () -> Void = {}

    /// Show the banner + play the sound even when the app is frontmost, so a take that finishes while you
    /// are looking at another Destination still announces itself.
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    /// Tap → open the Inbox (only for the brain-dump category the Kotlin notifier sets).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.notification.request.content.categoryIdentifier == BrainDumpRecordingKt.BRAIN_DUMP_NOTIFICATION_CATEGORY {
            onOpenInbox()
        }
        completionHandler()
    }
}

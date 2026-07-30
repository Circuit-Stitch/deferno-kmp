import Foundation
import OSLog
import SidecarKit

/// What a menu-bar row — or the global hotkey — can ask the app to do (#368 G26).
///
/// This protocol is the seam that keeps AppKit out of the tested path. `MenuBarController` below owns the
/// menu's **policy** (which rows exist, in what order, with what titles and key equivalents, and which
/// command each fires) and knows nothing about the shared shell, about windows, or about quitting;
/// `AppMenuBarCommands` (DefernoApp.swift) owns the **routing** and is the only place the two meet.
/// The split is what makes `MenuBarControllerTests` possible at all: `StatusItemController` and
/// `HotkeyCenter` touch process-global OS state (`NSStatusBar.system`, `RegisterEventHotKey`) and cannot
/// be faked from outside SidecarKit — `HotkeyRegistration.init` is `fileprivate` and there is no protocol
/// over either type — so the only testable surface is the one we own.
///
/// `AnyObject`-constrained: the real implementation closes over the app's `DefernoRoot` and is retained by
/// both the app and this controller, so it has to be a reference type (a struct would be copied into every
/// row's closure and the app would be handing out snapshots).
protocol MenuBarCommands: AnyObject {
    func openBrainDump()
    func showMainWindow()
    func quit()
}

/// One row of the status item's dropdown: what it says, its **menu** key equivalent, and the command it
/// fires. Plain data plus a closure, so the whole menu can be built and asserted with no `NSStatusItem`
/// anywhere near the test process (#368 G26).
///
/// `keyEquivalent` is AppKit's menu shortcut — "" for none, otherwise a lowercase character rendered with
/// AppKit's implicit ⌘ mask, live only while the menu is open. A *system-wide* shortcut is
/// `HotkeyCenter`'s job (#125), not this field's.
struct MenuBarRow {
    let title: String
    let keyEquivalent: String
    let invoke: () -> Void
}

/// The menu-bar **status item** (a flame in the system menu bar) plus the global **⌘⇧D** Brain dump
/// hotkey for the native macOS app (#368 G26).
///
/// ADR-0029 decision 2 pre-authorises exactly this shape: the app links SidecarKit's
/// `StatusItemController` / `HotkeyCenter` **in-process** — no launchd Helper, no socket, no IPC contract
/// — because these are affordances of the app's own identity rather than capabilities it is renting from
/// a privileged peer (ADR-0024's Helper is for TCC-attributed work the app cannot do itself). That is why
/// nothing here speaks the JSON protocol and why `contracts/sidecar/protocol-v1.md` stays silent about it.
///
/// Construction is inert on purpose: `install()` is what puts an item in the menu bar and takes a
/// process-global Carbon registration, so a test (or anything else) can hold a controller and inspect its
/// `rows()` without touching either.
final class MenuBarController {

    /// The global Brain dump hotkey, split out so the two halves are named once. **⌘⇧D** ("D" for dump),
    /// verified free against every shortcut this app binds: ⌘N (File → New), ⌘R (View → Refresh) and ⌘⇧E
    /// (the Extractor demo) in DefernoApp.swift, the View-local ⌘F in Tasks/ItemTreeView.swift, and ⌘↩ in
    /// TaskDetailView / DraftExtractorView. Private because `HotkeyModifier` is a SidecarKit type and this
    /// class deliberately keeps SidecarKit out of its own surface — the test bundle resolves symbols
    /// through BUNDLE_LOADER against the app host and does not link SidecarKit itself.
    private static let hotkeyKey = "d"
    private static let hotkeyModifiers: Set<HotkeyModifier> = [.command, .shift]

    /// The first Swift-side logger in this app: a refused hotkey is invisible by nature (no crash, no UI,
    /// the combo simply belongs to somebody else), and "my shortcut stopped working" has to be answerable
    /// from `log show` rather than from guesswork. Everything else the app diagnoses goes through Kotlin's
    /// os_log facade, which this cannot reach — the refusal happens before any shell call.
    private static let log = Logger(
        subsystem: Bundle.main.bundleIdentifier ?? "com.circuitstitch.deferno.macos",
        category: "menubar"
    )

    private let commands: MenuBarCommands

    /// Live OS resources, both nil until `install()`. Retaining the registration is not optional
    /// bookkeeping: `HotkeyRegistration` has no `deinit`, so dropping the token does **not** release the
    /// Carbon binding — it strands it, live and permanently un-unregisterable for the life of the process.
    /// (`HotkeyCenter.register` is not `@discardableResult` for exactly this reason.)
    private var statusItem: StatusItemController?
    private var hotkey: HotkeyRegistration?

    /// Whether each half is actually live. Not decoration: `isHotkeyRegistered == false` after an
    /// `install()` is the app's record that the OS refused ⌘⇧D, which is the difference between degrading
    /// gracefully and silently pretending the binding exists.
    private(set) var isStatusItemVisible = false
    private(set) var isHotkeyRegistered = false

    init(commands: MenuBarCommands) {
        self.commands = commands
    }

    /// The status item's rows, in order. Pure: no AppKit, no OS state, safe to call any number of times.
    ///
    /// Titles come from the shared Apple catalog (`app/shared-l10n/Localizable.xcstrings`, CLAUDE.md's
    /// no-hardcoded-strings rule) — SidecarKit has no catalog and takes an already-localized `title`, so
    /// the localization belongs on this side of the boundary.
    ///
    /// Every closure captures `commands`, never `self`. `self` would close a retain cycle the moment the
    /// rows reach AppKit: controller → `StatusItemController` → `NSStatusItem` → `NSMenu` → `NSMenuItem` →
    /// the boxed closure → controller.
    func rows() -> [MenuBarRow] {
        [
            // Brain dump first — it is the reason the status item exists at all (a thought you want out of
            // your head *now*, without hunting for the window), and ⌘⇧D below opens the very same overlay.
            // No key equivalent: `StatusItemMenuItem.keyEquivalent` renders with AppKit's implicit ⌘ mask
            // and cannot spell ⌘⇧D, so putting "d" here would advertise a ⌘D the app does not bind. The
            // row is the discoverable path; the hotkey is the fast one.
            MenuBarRow(title: L.string("braindump_title"), keyEquivalent: "") { [commands] in
                commands.openBrainDump()
            },
            // "Open main window", not "Open Deferno": the brand stays single-sourced in `common_app_name`,
            // and this row's real job is bringing back a window the person closed — the `main` scene is a
            // `Window`, not a `WindowGroup`, so closing it leaves the app running and windowless.
            MenuBarRow(title: L.string("shell_menu_open_main_window"), keyEquivalent: "") { [commands] in
                commands.showMainWindow()
            },
            // ⌘Q is the app's *real* Quit shortcut (SwiftUI's App menu owns it), so echoing it on this row
            // describes the app rather than promising something only this menu can do.
            MenuBarRow(title: L.string("shell_menu_quit"), keyEquivalent: "q") { [commands] in
                commands.quit()
            },
        ]
    }

    /// Put the flame in the menu bar and bind ⌘⇧D. Main thread (SidecarKit hops to it either way).
    ///
    /// Idempotent, and it has to be: the caller is `.onAppear` on the main window's content, which fires
    /// again every time a closed window is reopened. Neither AppKit nor Carbon dedupes for us — a second
    /// `NSStatusItem` is a second flame in the menu bar, and a second `RegisterEventHotKey` on a combo this
    /// process already owns would be refused, quietly replacing a working binding with a `nil`.
    ///
    /// Deliberately NOT gated on `GuiSession.available`: that check exists for the launchd Helper, which
    /// can be started headless (over SSH, with no window server) and then must not advertise the status-item
    /// capability at all (ADR-0025's graceful degradation). An `NSApplication`-hosted Dock app has a GUI
    /// session by construction, so the guard here would be unreachable code pretending to be caution.
    func install() {
        if statusItem == nil {
            // `onClick` is the click-through behaviour SidecarKit falls back to when no menu is attached.
            // Attaching one below suppresses it, so in this app it should never fire — but it is not
            // optional in the initialiser and a no-op would be a lie, so it gets the honest meaning a bare
            // click on the flame would have.
            let item = StatusItemController(onClick: { [commands] in commands.showMainWindow() })
            // Rows before visibility: `setMenu` is order-independent by design, and setting it first means
            // the item is never briefly on screen as a click-through button with different behaviour.
            item.setMenu(rows().map { row in
                StatusItemMenuItem(title: row.title, keyEquivalent: row.keyEquivalent, action: row.invoke)
            })
            item.setVisible(true)
            statusItem = item
            isStatusItemVisible = true
        }

        if hotkey == nil {
            let registration = HotkeyCenter.shared.register(
                key: Self.hotkeyKey,
                modifiers: Self.hotkeyModifiers,
                onFire: { [commands] in commands.openBrainDump() }
            )
            if let registration {
                hotkey = registration
                isHotkeyRegistered = true
            } else {
                // The OS hands a global combo to exactly one owner, so `nil` means somebody else already
                // holds ⌘⇧D (another app, or a System Settings shortcut). That is a legitimate machine
                // configuration, not an error: the status item's Brain dump row is the same command and is
                // still one click away, so the app degrades instead of complaining — but it says so.
                Self.log.notice("Global Brain dump hotkey (cmd-shift-D) refused by the OS; another app owns it. The menu-bar row still works.")
            }
        }
    }

    /// Take the flame back out of the menu bar and release the hotkey. Symmetric with `install()` and
    /// equally idempotent (calling it on a never-installed controller does nothing).
    ///
    /// The app never calls this — the status item's lifetime *is* the app's — but a Carbon registration is
    /// a process-global OS resource, and a type that takes one has to be able to give it back. It is also
    /// what makes `install()` genuinely re-runnable rather than merely guarded.
    func uninstall() {
        statusItem?.setVisible(false)
        statusItem = nil
        isStatusItemVisible = false

        hotkey?.unregister()
        hotkey = nil
        isHotkeyRegistered = false
    }
}

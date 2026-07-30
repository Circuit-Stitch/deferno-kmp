import AppKit
import CoreGraphics

/// Whether this process can reach the window server — the gate for AppKit UI (the status item) and
/// Carbon hotkeys (#125). A launchd LaunchAgent in the user's GUI session has one; a headless/SSH run
/// does not, and the helper then simply doesn't advertise the `statusItem`/`hotkeys` capabilities
/// (graceful degradation, ADR-0025) instead of crashing inside AppKit.
public enum GuiSession {
    public static var available: Bool {
        guard let dictionary = CGSessionCopyCurrentDictionary() else { return false }
        _ = dictionary // presence of a session dictionary is the signal; contents are irrelevant
        return true
    }
}

/// One row in the status item's optional dropdown menu (#368 G26). `keyEquivalent` is a *menu* key
/// equivalent — "" for none, otherwise a lowercase character AppKit renders with its default ⌘
/// modifier (so `"q"` shows ⌘Q). It is live only while the menu is open; a system-wide shortcut is
/// `HotkeyCenter`'s job (#125), not this one.
///
/// `title` is user-facing, so the *caller* supplies an already-localized string (CLAUDE.md: no
/// hardcoded user-facing strings — the native macOS app reads `app/shared-l10n/Localizable.xcstrings`).
/// `action` runs on the main thread, on the AppKit click.
public struct StatusItemMenuItem {

    public let title: String
    public let keyEquivalent: String
    public let action: () -> Void

    public init(title: String, keyEquivalent: String = "", action: @escaping () -> Void) {
        self.title = title
        self.keyEquivalent = keyEquivalent
        self.action = action
    }
}

/// One connection's menu-bar **status item** (#125, ADR-0024): an `NSStatusItem` (flame icon) whose
/// clicks invoke `onClick` — the connection routes them as `statusItemClicked` pushes. All AppKit work
/// happens on the main thread, which `deferno-sidecar` keeps running (`NSApplication.run` in real
/// mode). The owning provider removes the item (`setVisible(false)`) when its connection closes, so
/// the item is visible only while the app holds a connection — "appears while the app runs".
///
/// The native macOS app links this same type in-process (ADR-0029 §2, no socket) and wants a *menu*
/// rather than a bare click, so `setMenu(_:)` is layered on additively (#368 G26): the launchd
/// Helper never calls it and its click-through path through this class is unchanged.
public final class StatusItemController: NSObject {

    private let onClick: () -> Void

    /// Both of these are **main-thread-confined** — every read/write below happens inside `runOnMain`,
    /// which is why neither needs a lock even though the Helper drives this class from a connection's
    /// read thread (see `RealCapabilityProvider` "status item + hotkeys").
    private var statusItem: NSStatusItem?

    /// The rows last handed to `setMenu(_:)`, retained here rather than only on the `NSMenu`: the menu
    /// belongs to the `NSStatusItem`, and `setVisible(false)` destroys that item — without this a
    /// hide/show cycle would silently come back menu-less. `nil` = the click-through behaviour.
    private var menuItems: [StatusItemMenuItem]?

    public init(onClick: @escaping () -> Void) {
        self.onClick = onClick
    }

    public func setVisible(_ visible: Bool) {
        runOnMain {
            if visible {
                guard self.statusItem == nil else { return } // already visible — idempotent
                let item = NSStatusBar.system.statusItem(withLength: NSStatusItem.squareLength)
                if let button = item.button {
                    // The Deferno flame (the launcher-icon motif); fall back to text if the symbol is
                    // ever unavailable.
                    if let image = NSImage(systemSymbolName: "flame", accessibilityDescription: "Deferno") {
                        button.image = image
                    } else {
                        button.title = "🔥"
                    }
                    button.target = self
                    button.action = #selector(self.clicked)
                }
                // Re-attach a menu set before this show (or before an earlier hide) onto the brand-new
                // item (#368 G26). Deliberately conditional: with no menu set this method touches
                // nothing it didn't touch before, so the Helper's path is byte-for-byte what it was.
                if let menu = self.buildMenu() { item.menu = menu }
                self.statusItem = item
            } else {
                guard let item = self.statusItem else { return }
                NSStatusBar.system.removeStatusItem(item)
                self.statusItem = nil
            }
        }
    }

    /// Replace the status item's dropdown menu (#368 G26). Order-independent: call it before or after
    /// `setVisible(true)`, and the rows survive a `setVisible(false)` → `setVisible(true)` cycle.
    ///
    /// `nil` (the default — the launchd Helper never calls this) restores the click-through behaviour
    /// `RealCapabilityProvider.setStatusItem` relies on: while `NSStatusItem.menu` is nil AppKit sends
    /// the button's action, so a click fires `onClick` directly with no menu. Attaching a menu
    /// suppresses that action instead of replacing the wiring, which is what makes the swap reversible.
    ///
    /// An empty array is treated as `nil`: an `NSMenu` with no rows pops an empty box open and eats the
    /// click, which is never what a caller means — and it would strand the item with no way to act.
    public func setMenu(_ items: [StatusItemMenuItem]?) {
        runOnMain {
            self.menuItems = (items?.isEmpty == false) ? items : nil
            // Assigning nil here is the *restore* path, so unlike `setVisible` this one is unconditional.
            self.statusItem?.menu = self.buildMenu()
        }
    }

    @objc private func clicked() {
        onClick()
    }

    // MARK: menu internals (#368 G26)

    /// Build a fresh `NSMenu` from `menuItems`, or nil for click-through. Main thread only (all callers
    /// are inside `runOnMain`). Always fresh: an `NSMenu` is owned by the status item it is attached to,
    /// and rebuilding keeps each row and its boxed closure in lockstep with the current rows.
    private func buildMenu() -> NSMenu? {
        guard let rows = menuItems, !rows.isEmpty else { return nil }
        // A status-item menu never displays its own title, so there is no user-facing string to localize.
        let menu = NSMenu(title: "")
        // Without this, `NSMenu` auto-enabling asks each row's target to validate it and greys out
        // anything that doesn't opt in — the classic "my menu-bar menu is dead on arrival" failure.
        // Every row here is unconditionally actionable, so enablement is ours to state, not to infer.
        menu.autoenablesItems = false
        for row in rows {
            let item = NSMenuItem(
                title: row.title,
                action: #selector(self.menuItemFired(_:)),
                keyEquivalent: row.keyEquivalent
            )
            // `NSMenuItem.target` is **weak**. A per-row closure box used as the target would be the
            // only strong reference to itself, so it would die the moment this loop moved on and the
            // click would go nowhere at all (a nil target silently falls back to the responder chain,
            // which has no such selector — the failure is a menu row that does nothing, with no crash
            // and no log). So the target is `self` — the controller, kept alive by whoever owns it,
            // exactly like the status button's target above — and the closure rides in
            // `representedObject`, which *is* a strong property and therefore outlives this loop.
            item.target = self
            item.representedObject = MenuAction(row.action)
            menu.addItem(item)
        }
        return menu
    }

    /// The one selector every row targets; it recovers its own closure from the sender.
    @objc private func menuItemFired(_ sender: NSMenuItem) {
        (sender.representedObject as? MenuAction)?.run()
    }

    /// Strong box for a row's closure — see `buildMenu()` for why the closure can't be the target
    /// itself. `NSObject` so it round-trips through the Obj-C `representedObject` property untouched.
    private final class MenuAction: NSObject {
        private let body: () -> Void

        init(_ body: @escaping () -> Void) {
            self.body = body
        }

        func run() {
            body()
        }
    }

    private func runOnMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread {
            body()
        } else {
            DispatchQueue.main.sync(execute: body)
        }
    }
}

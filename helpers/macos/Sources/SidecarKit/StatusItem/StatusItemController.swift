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

/// What a status item does when it is clicked (#368 G26) — the two shapes it can take, chosen once at
/// construction. Stating them as one exhaustive value is what keeps the type honest: a click closure
/// *plus* an optional menu would be two settings with an implicit precedence (AppKit suppresses the
/// button's action whenever a menu is attached), so every caller of the menu form would have to supply a
/// click closure it knows can never fire.
public enum StatusItemBehavior {

    /// A bare button: a click invokes this directly. The launchd Helper's mode — `RealCapabilityProvider`
    /// routes each click back to the connected app as a `statusItemClicked` push (#125, ADR-0024).
    case click(() -> Void)

    /// A dropdown: a click opens the menu and each row invokes its own action. The native macOS app's
    /// mode (linked in-process, ADR-0029 §2 — no socket, no wire protocol). Rows are rendered in order.
    /// Pass the rows you have: `.menu([])` is an item that opens an empty box, which is the honest
    /// rendering of "no rows" and not something any caller wants.
    case menu([StatusItemMenuItem])
}

/// One connection's menu-bar **status item** (#125, ADR-0024): an `NSStatusItem` showing the Deferno
/// flame, behaving as its [StatusItemBehavior] says. All AppKit work happens on the main thread, which
/// `deferno-sidecar` keeps running (`NSApplication.run` in real mode). The owning provider removes the
/// item (`setVisible(false)`) when its connection closes, so the item is visible only while the app holds
/// a connection — "appears while the app runs".
public final class StatusItemController: NSObject {

    /// Immutable, which is what makes `setVisible` the only lifecycle question this type has. The
    /// `NSStatusItem` is destroyed and rebuilt across a hide/show cycle, so the behaviour has to outlive
    /// it — holding it as a `let` means it does, with no separate "remember the rows" bookkeeping and no
    /// order-dependence between configuring and showing.
    private let behavior: StatusItemBehavior

    /// **Main-thread-confined** — every read/write happens inside `runOnMain`, which is why it needs no
    /// lock even though the Helper drives this class from a connection's read thread (see
    /// `RealCapabilityProvider` "status item + hotkeys").
    private var statusItem: NSStatusItem?

    public init(behavior: StatusItemBehavior) {
        self.behavior = behavior
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
                    // Only in `.click` mode: AppKit sends the button's action only while `item.menu` is
                    // nil, so wiring target/action in `.menu` mode would be a pair that can never fire.
                    if case .click = self.behavior {
                        button.target = self
                        button.action = #selector(self.clicked)
                    }
                }
                if case .menu(let rows) = self.behavior {
                    item.menu = self.buildMenu(rows)
                }
                self.statusItem = item
            } else {
                guard let item = self.statusItem else { return }
                NSStatusBar.system.removeStatusItem(item)
                self.statusItem = nil
            }
        }
    }

    @objc private func clicked() {
        guard case .click(let action) = behavior else { return }
        action()
    }

    // MARK: menu internals (#368 G26)

    /// Build a fresh `NSMenu` for [rows]. Main thread only (the caller is inside `runOnMain`). Fresh on
    /// every show: an `NSMenu` is owned by the status item it is attached to, and that item is recreated
    /// by each `setVisible(true)`.
    private func buildMenu(_ rows: [StatusItemMenuItem]) -> NSMenu {
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

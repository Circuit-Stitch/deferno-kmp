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

/// One connection's menu-bar **status item** (#125, ADR-0024): an `NSStatusItem` (flame icon) whose
/// clicks invoke `onClick` — the connection routes them as `statusItemClicked` pushes. All AppKit work
/// happens on the main thread, which `deferno-sidecar` keeps running (`NSApplication.run` in real
/// mode). The owning provider removes the item (`setVisible(false)`) when its connection closes, so
/// the item is visible only while the app holds a connection — "appears while the app runs".
public final class StatusItemController: NSObject {

    private let onClick: () -> Void
    private var statusItem: NSStatusItem?

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
                self.statusItem = item
            } else {
                guard let item = self.statusItem else { return }
                NSStatusBar.system.removeStatusItem(item)
                self.statusItem = nil
            }
        }
    }

    @objc private func clicked() {
        onClick()
    }

    private func runOnMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread {
            body()
        } else {
            DispatchQueue.main.sync(execute: body)
        }
    }
}

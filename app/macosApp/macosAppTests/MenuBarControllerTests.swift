import Testing
@testable import macosApp

/// Coverage of the menu-bar **policy** (#368 G26): which rows the status item offers, in what order, with
/// which titles and key equivalents, and which command each one invokes.
///
/// Everything the menu-bar feature does that a test *can* pin lives in `MenuBarController.rows()`, and
/// that is not an accident — it is why the type exists. `StatusItemController` and `HotkeyCenter` touch
/// process-global OS state (`NSStatusBar.system`, `RegisterEventHotKey`) and cannot be faked from outside
/// SidecarKit: `HotkeyRegistration.init` is `fileprivate` and neither type sits behind a protocol. So the
/// wiring is kept deliberately thin over the policy, and the policy is asserted here with no AppKit in
/// sight. `install()` is **never** called from this suite: it would put a real flame in whoever is running
/// the tests' menu bar and take a real system-wide ⌘⇧D away from whatever else owns it.
///
/// Titles are read through the same `L.string` the production code uses rather than hardcoded in English —
/// this is the macOS app, whose strings come from `app/shared-l10n/Localizable.xcstrings` in five locales
/// (CLAUDE.md), so an English literal here would fail on a German-locale machine. What that asserts is the
/// **key choice**, which is the part that can actually be got wrong; `catalogKeysResolve` below covers the
/// other half — that the keys exist at all.
///
/// swift-testing, matching the sibling `BreakdownEngineTests` (`DefernoThemeTests` is XCTest; both styles
/// coexist in this target) and its hand-rolled-spy shape — no mocking framework in this repo.
@MainActor
struct MenuBarControllerTests {

    // MARK: Fakes

    /// Records every command it receives, in order, so a test can assert both "the right one" and "only
    /// that one". A class, not an actor: `MenuBarCommands` is synchronous and main-thread-confined (AppKit
    /// menu actions and Carbon hotkey callbacks both arrive on the main thread), so there is nothing to
    /// await and no isolation to model.
    final class SpyCommands: MenuBarCommands {

        enum Command: String {
            case openBrainDump
            case showMainWindow
            case quit
        }

        private(set) var calls: [Command] = []

        func openBrainDump() { calls.append(.openBrainDump) }
        func showMainWindow() { calls.append(.showMainWindow) }
        func quit() { calls.append(.quit) }
    }

    // MARK: Tests

    /// The whole menu, in one assertion: exactly three rows, in this order, with these titles. Comparing
    /// the mapped array (rather than indexing) is what makes a dropped or reordered row fail loudly instead
    /// of trapping on an out-of-range index.
    @Test func rowsAreBrainDumpThenMainWindowThenQuit() {
        let controller = MenuBarController(commands: SpyCommands())

        #expect(controller.rows().map(\.title) == [
            L.string("braindump_title"),
            L.string("shell_menu_open_main_window"),
            L.string("shell_menu_quit"),
        ])
    }

    /// Only Quit carries a menu key equivalent, and it is ⌘Q — the shortcut the App menu already binds, so
    /// the row describes the app rather than promising something only this menu can do. Brain dump gets
    /// none on purpose: `StatusItemMenuItem.keyEquivalent` renders with AppKit's implicit ⌘ mask and cannot
    /// spell the global ⌘⇧D, so "d" here would advertise a ⌘D that does not exist.
    @Test func onlyQuitAdvertisesAKeyEquivalent() {
        let controller = MenuBarController(commands: SpyCommands())

        #expect(controller.rows().map(\.keyEquivalent) == ["", "", "q"])
    }

    /// Each row invokes its own command, exactly once, and nothing else. A fresh spy per row is what makes
    /// the second half of that claim testable — a shared spy could only show that the right call happened,
    /// not that no other one did.
    @Test func eachRowInvokesExactlyItsOwnCommand() {
        let expected: [SpyCommands.Command] = [.openBrainDump, .showMainWindow, .quit]

        for (index, command) in expected.enumerated() {
            let spy = SpyCommands()
            let controller = MenuBarController(commands: spy)

            controller.rows()[index].invoke()

            #expect(spy.calls == [command])
        }
    }

    /// Constructing a controller — and asking it for its rows — has no effect on the world. This is the
    /// invariant that lets the rest of this suite exist: if the initialiser placed the status item or took
    /// the Carbon registration, running these tests would hijack the developer's menu bar and their ⌘⇧D.
    /// `install()` is the only thing with effects, and nothing here calls it.
    @Test func constructionTouchesNeitherTheStatusBarNorTheHotkey() {
        let spy = SpyCommands()
        let controller = MenuBarController(commands: spy)

        _ = controller.rows()

        #expect(controller.isStatusItemVisible == false)
        #expect(controller.isHotkeyRegistered == false)
        // Building the rows must not *run* them — the closures are handed to AppKit, not invoked.
        #expect(spy.calls.isEmpty)
    }

    /// `uninstall()` is symmetric with `install()` and safe on a controller that was never installed, so
    /// teardown never has to ask whether setup got that far.
    @Test func uninstallBeforeInstallIsANoOp() {
        let controller = MenuBarController(commands: SpyCommands())

        controller.uninstall()

        #expect(controller.isStatusItemVisible == false)
        #expect(controller.isHotkeyRegistered == false)
    }

    /// The other half of the title assertions: that the three keys are actually **in** the catalog.
    /// `L.string` falls back to returning the key itself when a lookup misses, so comparing two `L.string`
    /// calls would happily agree on a typo. `Bundle.main` here is the test *host* (Deferno.app, pinned by
    /// TEST_HOST in project.yml), which is where the compiled `Localizable.strings` lives.
    @Test func catalogKeysResolve() {
        for key in ["braindump_title", "shell_menu_open_main_window", "shell_menu_quit"] {
            #expect(L.string(key) != key, "\(key) is missing from Localizable.xcstrings")
        }
    }
}

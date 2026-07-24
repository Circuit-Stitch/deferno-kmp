import XCTest
@testable import iosApp

/// #358 — the Main shell's reveal drawer kept its navigation rows in the accessibility tree even while
/// the drawer was **visually closed**, so VoiceOver could reach (and activate) the hidden nav — and,
/// because the drawer is the first `ZStack` child, likely *before* the visible content. `ShellAccessibility`
/// is the seam that decides which of the two overlaid layers VoiceOver may reach; these tests pin the
/// invariant `MainShellView` relies on: exactly one layer is reachable in each settled drawer state.
final class ShellAccessibilityTests: XCTestCase {

    /// The bug: while closed, the drawer's phantom nav rows must NOT be reachable by VoiceOver, and the
    /// visible content behind the top bar must be.
    func testClosedDrawerIsHiddenAndContentIsReachable() {
        let a11y = ShellAccessibility(drawerOpen: false)
        XCTAssertTrue(a11y.drawerHidden, "closed: the off-screen drawer nav rows must be hidden from VoiceOver")
        XCTAssertFalse(a11y.contentHidden, "closed: the visible content must stay reachable")
    }

    /// While open, the drawer IS the focus target, and the content it slid aside must not be reachable
    /// underneath it.
    func testOpenDrawerIsReachableAndCoveredContentIsHidden() {
        let a11y = ShellAccessibility(drawerOpen: true)
        XCTAssertFalse(a11y.drawerHidden, "open: the drawer's nav rows must be reachable")
        XCTAssertTrue(a11y.contentHidden, "open: the covered content must be hidden from VoiceOver")
    }

    /// The core invariant regardless of state: never both reachable (the #358 bug) and never both hidden
    /// (a dead screen for VoiceOver) — exactly one layer is reachable.
    func testExactlyOneLayerIsReachableInEitherState() {
        for open in [true, false] {
            let a11y = ShellAccessibility(drawerOpen: open)
            XCTAssertNotEqual(
                a11y.drawerHidden, a11y.contentHidden,
                "exactly one of drawer/content must be VoiceOver-reachable (drawerOpen=\(open))"
            )
        }
    }
}

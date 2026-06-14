import XCTest
import Deferno
@testable import iosApp

/// Unit coverage for the iOS Tasks **secondary-pane precedence** (#28). `resolveSecondarySlot`
/// (`Common/CommonViews.swift`) is a **hand-port** of the shared Kotlin `resolveSecondarySlot`
/// (`feature/tasks/ui/.../SecondarySlot.kt`) — the Compose UI module can't target iOS, so the iOS
/// View reimplements the rule (ADR-0004 / #27). The Kotlin side is pinned by `SecondarySlotTest`;
/// this is its Swift twin, so the two copies can't drift. The rule: recency (the foregrounded pane)
/// wins only when its own slot is open, else the tree slot is preferred over the detail slot, else
/// nothing.
final class SecondarySlotTests: XCTestCase {

    private struct Case {
        let activePane: TaskPane
        let hasDetail: Bool
        let hasTree: Bool
        let expected: SecondarySlot
    }

    /// The exhaustive table from `SecondarySlotTest.resolvesEveryCombination` — activePane ∈
    /// {list, detail, tree} × hasDetail × hasTree = 12 rows — ported 1:1 to guard against drift.
    func testResolvesEveryCombination() {
        let cases: [Case] = [
            Case(activePane: .list,   hasDetail: false, hasTree: false, expected: .none),
            Case(activePane: .list,   hasDetail: false, hasTree: true,  expected: .tree),
            Case(activePane: .list,   hasDetail: true,  hasTree: false, expected: .detail),
            Case(activePane: .list,   hasDetail: true,  hasTree: true,  expected: .tree),
            Case(activePane: .detail, hasDetail: false, hasTree: false, expected: .none),
            Case(activePane: .detail, hasDetail: false, hasTree: true,  expected: .tree),
            Case(activePane: .detail, hasDetail: true,  hasTree: false, expected: .detail),
            Case(activePane: .detail, hasDetail: true,  hasTree: true,  expected: .detail),
            Case(activePane: .tree,   hasDetail: false, hasTree: false, expected: .none),
            Case(activePane: .tree,   hasDetail: false, hasTree: true,  expected: .tree),
            Case(activePane: .tree,   hasDetail: true,  hasTree: false, expected: .detail),
            Case(activePane: .tree,   hasDetail: true,  hasTree: true,  expected: .tree),
        ]
        for c in cases {
            XCTAssertEqual(
                resolveSecondarySlot(activePane: c.activePane, hasDetail: c.hasDetail, hasTree: c.hasTree),
                c.expected,
                "activePane=\(c.activePane), hasDetail=\(c.hasDetail), hasTree=\(c.hasTree)"
            )
        }
    }

    /// Recency wins when both slots are open — the foregrounded pane stays foregrounded.
    func testRecencyWinsWhenBothSlotsOpen() {
        XCTAssertEqual(resolveSecondarySlot(activePane: .tree,   hasDetail: true, hasTree: true), .tree)
        XCTAssertEqual(resolveSecondarySlot(activePane: .detail, hasDetail: true, hasTree: true), .detail)
    }

    /// The tree→child drill-in regression (#29 / #67): activePane = tree must keep the tree
    /// foregrounded even when a detail slot is also open — a fixed precedence would snap to detail.
    func testTreeDrillInKeepsTreeForegrounded() {
        XCTAssertEqual(resolveSecondarySlot(activePane: .tree, hasDetail: true, hasTree: true), .tree)
    }

    /// activePane names a slot that isn't open → fall through to whichever slot remains.
    func testRecencyFallsThroughWhenItsSlotIsClosed() {
        XCTAssertEqual(resolveSecondarySlot(activePane: .tree,   hasDetail: true,  hasTree: false), .detail)
        XCTAssertEqual(resolveSecondarySlot(activePane: .detail, hasDetail: false, hasTree: true),  .tree)
    }

    /// Nothing open → no secondary pane, regardless of the foregrounded pane.
    func testNoneWhenNothingOpen() {
        for pane in [TaskPane.list, .detail, .tree] {
            XCTAssertEqual(
                resolveSecondarySlot(activePane: pane, hasDetail: false, hasTree: false),
                .none,
                "pane=\(pane) with no slots open"
            )
        }
    }

    // MARK: - tasksNavPath (compact NavigationStack projection)

    /// The compact-width stack path mirrors `resolveSecondarySlot`: present slots only, foreground on top.
    func testNavPathProjectsPresentSlots() {
        XCTAssertEqual(tasksNavPath(activePane: .list,   hasDetail: false, hasTree: false), [])
        XCTAssertEqual(tasksNavPath(activePane: .detail, hasDetail: true,  hasTree: false), [.detail])
        XCTAssertEqual(tasksNavPath(activePane: .tree,   hasDetail: false, hasTree: true),  [.tree])
        // foreground recency wins the top slot when both are open
        XCTAssertEqual(tasksNavPath(activePane: .tree,   hasDetail: true,  hasTree: true),  [.detail, .tree])
        XCTAssertEqual(tasksNavPath(activePane: .detail, hasDetail: true,  hasTree: true),  [.tree, .detail])
    }

    /// activePane = list with both slots open falls through to the tree-preferred order (matches
    /// `resolveSecondarySlot`), so the projection never diverges from the resolved foreground slot.
    func testNavPathBothOpenWithoutRecencyPrefersTreeTop() {
        XCTAssertEqual(tasksNavPath(activePane: .list, hasDetail: true, hasTree: true), [.detail, .tree])
    }

    /// The path top always equals the resolved secondary slot — the two stay consistent across every
    /// combination, so native back/swipe pops exactly the foregrounded slot.
    func testNavPathTopMatchesResolvedSlot() {
        for pane in [TaskPane.list, .detail, .tree] {
            for hasDetail in [false, true] {
                for hasTree in [false, true] {
                    let path = tasksNavPath(activePane: pane, hasDetail: hasDetail, hasTree: hasTree)
                    let resolved = resolveSecondarySlot(activePane: pane, hasDetail: hasDetail, hasTree: hasTree)
                    switch resolved {
                    case .none:   XCTAssertTrue(path.isEmpty, "pane=\(pane) d=\(hasDetail) t=\(hasTree)")
                    case .detail: XCTAssertEqual(path.last, .detail, "pane=\(pane) d=\(hasDetail) t=\(hasTree)")
                    case .tree:   XCTAssertEqual(path.last, .tree, "pane=\(pane) d=\(hasDetail) t=\(hasTree)")
                    }
                }
            }
        }
    }
}

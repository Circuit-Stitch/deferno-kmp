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
}

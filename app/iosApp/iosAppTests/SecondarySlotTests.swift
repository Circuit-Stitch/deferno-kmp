import XCTest
import Deferno
@testable import iosApp

/// Unit coverage for the iOS Tasks **secondary-pane precedence** (#28). `resolveSecondarySlot`
/// (`Common/CommonViews.swift`) is a **hand-port** of the shared Kotlin `resolveSecondarySlot`
/// (`feature/tasks/ui/.../SecondarySlot.kt`) — the Compose UI module can't target iOS, so the iOS View
/// reimplements the rule (ADR-0004 / #27). Since the Item tree became the always-present **primary** pane
/// (#227, ADR-0034), detail is the only secondary slot: the rule is simply "show the detail iff one is
/// open." This is the Swift twin of the Kotlin `SecondarySlotTest`, so the two copies can't drift.
final class SecondarySlotTests: XCTestCase {

    /// `resolveSecondarySlot` is now a two-state function of `hasDetail`.
    func testResolvesEveryCombination() {
        XCTAssertEqual(resolveSecondarySlot(hasDetail: false), .none)
        XCTAssertEqual(resolveSecondarySlot(hasDetail: true), .detail)
    }

    /// The compact-width `NavigationStack` path projects the present detail slot: `[.detail]` when open,
    /// empty otherwise (the tree is the stack root, never in the pushed path).
    func testNavPathProjectsPresentSlots() {
        XCTAssertEqual(tasksNavPath(hasDetail: false), [])
        XCTAssertEqual(tasksNavPath(hasDetail: true), [.detail])
    }

    /// The path top always equals the resolved secondary slot, so a native back/swipe pops exactly the
    /// foregrounded detail.
    func testNavPathTopMatchesResolvedSlot() {
        for hasDetail in [false, true] {
            let path = tasksNavPath(hasDetail: hasDetail)
            switch resolveSecondarySlot(hasDetail: hasDetail) {
            case .none: XCTAssertTrue(path.isEmpty, "hasDetail=\(hasDetail)")
            case .detail: XCTAssertEqual(path.last, .detail, "hasDetail=\(hasDetail)")
            }
        }
    }
}

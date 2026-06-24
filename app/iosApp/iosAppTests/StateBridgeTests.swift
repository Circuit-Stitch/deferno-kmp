import Combine
import XCTest
import Deferno
@testable import iosApp

/// Smoke coverage for the state-observation path (#28 / #51): how a shared Decompose component's
/// `StateFlow` / co-resident slot reaches a SwiftUI `ObservableObject`, all through SKIE (ADR-0003).
/// Component state is `component.state: SkieSwiftStateFlow` → `StateFlowObserver`. The Decompose
/// co-resident detail slot is now exposed as a `StateFlow` mirror of the active child
/// (`TasksComponent.activeDetail`, via `Value.asStateFlow`), so SKIE bridges it like any other flow —
/// `SkieSwiftOptionalStateFlow` → `OptionalStateFlowObserver`. These tests prove both deliver the real
/// component state on the main thread, driving the genuine shared components through the same
/// `DefernoDemo` harness the simulator app runs — only the data is a fixture.
@MainActor
final class StateBridgeTests: XCTestCase {

    private var demo: DefernoDemo!
    private var cancellables: Set<AnyCancellable> = []

    override func setUp() {
        super.setUp()
        demo = DefernoDemo()
    }

    override func tearDown() {
        cancellables.removeAll()
        demo?.destroy()
        demo = nil
        super.tearDown()
    }

    /// The tree state starts at the `stateIn` seed (empty); once the observer subscribes, the demo
    /// repository's items flow through SKIE's `SkieSwiftStateFlow` to a published update on the main thread.
    func testItemTreeStateReachesObserver() {
        let observer = StateFlowObserver(demo.tasks.tree.state)

        let loaded = expectation(description: "the tree bridge delivers the demo items")
        observer.$value
            .sink { state in
                let titles = state.rows.map(\.item.title)
                if titles.contains("Water the plants"), titles.contains("Plan the spring launch") {
                    loaded.fulfill()
                }
            }
            .store(in: &cancellables)

        wait(for: [loaded], timeout: 5)
    }

    /// Opening a tree row's detail drives the retained shared component to open its co-resident **detail**
    /// slot (ADR-0007) — the slot the two-pane `TasksScreen` observes goes from nil to the selected Task,
    /// all on the main thread. This exercises the thin-view contract end to end: the View forwards an
    /// intent, the shared component owns the navigation, and the slot bridge publishes the result.
    func testItemSelectionOpensDetailSlot() {
        // 1) Let the tree load so we can open a real row by the same id + kind the View forwards.
        let treeObserver = StateFlowObserver(demo.tasks.tree.state)
        var target: ItemRow?
        let listed = expectation(description: "the tree loads the demo items")
        treeObserver.$value
            .sink { state in
                if let row = state.rows.first(where: { $0.item.title == "Water the plants" }) {
                    target = row
                    listed.fulfill()
                }
            }
            .store(in: &cancellables)
        wait(for: [listed], timeout: 5)
        guard let target else { return XCTFail("the demo tree never delivered \"Water the plants\"") }

        // 2) The detail slot is empty until a selection; opening the row's detail opens it on that Task.
        let detail = OptionalStateFlowObserver(demo.tasks.activeDetail)
        XCTAssertNil(detail.value, "no detail pane is open before a selection")

        let opened = expectation(description: "opening a row's detail opens its detail slot")
        detail.$value
            .compactMap { $0 }
            .sink { component in
                XCTAssertEqual(
                    BridgeKt.detailKey(component: component),
                    target.item.id,
                    "the opened detail slot should be rooted at the opened Task"
                )
                opened.fulfill()
            }
            .store(in: &cancellables)

        demo.tasks.tree.onOpenDetail(id: target.item.id, kind: target.item.kind)
        wait(for: [opened], timeout: 5)
    }
}

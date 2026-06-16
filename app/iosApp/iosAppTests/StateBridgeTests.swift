import Combine
import XCTest
import Deferno
@testable import iosApp

/// Smoke coverage for the **SKIE-free state bridge** (#28 / #51): the path that carries a shared
/// Decompose component's `StateFlow` / co-resident slot to a SwiftUI `ObservableObject`. No released
/// SKIE supports Kotlin 2.4.0 yet, so the Views observe through hand-written `StateFlowBridge` /
/// `DetailSlot` → `StateFlowObserver` / `DetailSlotObserver` wrappers (`Bridge/ObservableState.swift`
/// + `…/ios/bridge/Bridge.kt`). These tests prove that path actually delivers the real component
/// state on the main thread, driving the genuine shared components through the same `DefernoDemo`
/// harness the simulator app runs — only the data source is a fixture.
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

    /// The tree bridge starts at the `stateIn` seed (empty); once the observer subscribes, the demo
    /// repository's items flow through `itemTreeStateBridge` to a published update on the main thread.
    func testItemTreeStateReachesObserver() {
        let observer = StateFlowObserver(BridgeKt.itemTreeStateBridge(component: demo.tasks.tree))

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
        let treeObserver = StateFlowObserver(BridgeKt.itemTreeStateBridge(component: demo.tasks.tree))
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
        let detail = DetailSlotObserver(demo.tasks.detail)
        XCTAssertNil(detail.current, "no detail pane is open before a selection")

        let opened = expectation(description: "opening a row's detail opens its detail slot")
        detail.$current
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

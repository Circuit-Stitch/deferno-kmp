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

    /// The list bridge starts at the `stateIn` seed (empty); once the observer subscribes, the demo
    /// repository's tasks flow through `taskListStateBridge` to a published update on the main thread.
    func testTaskListStateReachesObserver() {
        let observer = StateFlowObserver(BridgeKt.taskListStateBridge(component: demo.tasks.list))

        let loaded = expectation(description: "the list bridge delivers the demo tasks")
        observer.$value
            .sink { state in
                let titles = state.tasks.map(\.title)
                if titles.contains("Water the plants"), titles.contains("Plan the spring launch") {
                    loaded.fulfill()
                }
            }
            .store(in: &cancellables)

        wait(for: [loaded], timeout: 5)
    }

    /// Tapping a list row drives the retained shared component to open its co-resident **detail** slot
    /// (ADR-0007) — the slot the two-pane `TasksScreen` observes goes from nil to the selected Task,
    /// all on the main thread. This exercises the thin-view contract end to end: the View forwards an
    /// intent, the shared component owns the navigation, and the slot bridge publishes the result.
    func testTaskSelectionOpensDetailSlot() {
        // 1) Let the list load so we can select a real Task by the same id the View forwards.
        let listObserver = StateFlowObserver(BridgeKt.taskListStateBridge(component: demo.tasks.list))
        var target: Task?
        let listed = expectation(description: "the list loads the demo tasks")
        listObserver.$value
            .sink { state in
                if let task = state.tasks.first(where: { $0.title == "Water the plants" }) {
                    target = task
                    listed.fulfill()
                }
            }
            .store(in: &cancellables)
        wait(for: [listed], timeout: 5)
        guard let target else { return XCTFail("the demo list never delivered \"Water the plants\"") }

        // 2) The detail slot is empty until a selection; the row tap opens it on the selected Task.
        let detail = DetailSlotObserver(demo.tasks.detail)
        XCTAssertNil(detail.current, "no detail pane is open before a selection")

        let opened = expectation(description: "selecting a task opens its detail slot")
        detail.$current
            .compactMap { $0 }
            .sink { component in
                XCTAssertEqual(
                    BridgeKt.detailKey(component: component),
                    BridgeKt.taskKey(task: target),
                    "the opened detail slot should be rooted at the tapped Task"
                )
                opened.fulfill()
            }
            .store(in: &cancellables)

        demo.tasks.list.onTaskClicked(id: target.id)
        wait(for: [opened], timeout: 5)
    }
}

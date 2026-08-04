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

/// The agenda chip's reading, as the shared bridge hands it over (#402, ADR-0053 decisions 4 and 7).
///
/// This is the only thing on the Apple side that can catch a chip regression. The chip used to be a
/// five-`if` chain over `WorkingState` inside `CalendarView`, ending in `return status.label` — which is
/// the *Tasks* vocabulary ("Open", "Done") — so the four readings this slice adds (`DoneOnTime`,
/// `DoneLate`, `Missed`, `Unknown`) would have dropped through that catch-all and printed Tasks words on
/// a calendar surface, with nothing failing to compile. The mapping now lives in one Kotlin `when`
/// (`ShellBridgeKt.occurrenceStatusToken`); these assertions pin it from the platform that renders it.
///
/// It lives in this file rather than its own because a new `.swift` would need a hand-edited
/// `project.pbxproj` here and a `project.yml` regeneration on macOS. The macOS twin sits in
/// `macosAppTests/DefernoThemeTests.swift` for the same reason — the two are byte-for-byte, keep them so.
final class CalendarStatusTokenTests: XCTestCase {

    /// One agenda row. `seriesId` + a recurring `kind` make it an actionable firing (the case that
    /// carries a reading); pass `seriesId: nil, kind: .task` for the one-off dated Task that does not.
    private func firing(
        _ occurrence: OccurrenceState?,
        status: WorkingState = .open,
        seriesId: String? = "series-1",
        kind: ItemKind? = .habit
    ) -> CalendarFiring {
        // The clock is irrelevant to the reading — it was resolved upstream, against the user's local
        // today — so both instants are epoch and the row is all-day.
        let epoch = KotlinInstant.companion.fromEpochMilliseconds(epochMilliseconds: 0)
        return CalendarFiring(
            item: CalendarItem(
                id: "row-1",
                taskId: "definition-1",
                seriesId: seriesId,
                title: "Water the plants",
                date: LocalDate(year: 2026, month: 6, day: 1),
                start: epoch,
                end: epoch,
                allDay: true,
                status: status,
                kind: kind,
                source: .deferno,
                labels: []
            ),
            occurrence: occurrence
        )
    }

    private func token(_ occurrence: OccurrenceState?) -> String {
        ShellBridgeKt.occurrenceStatusToken(firing: firing(occurrence))
    }

    /// A past unfinished firing inside synced coverage reads "Missed" — factual, and in the register the
    /// shared catalog fixes. Gentleness is vocabulary, not suppression (ADR-0053 decision 7).
    func testMissedFiringReadsTheMissedKey() {
        XCTAssertEqual(token(.missed), "common_status_missed")
    }

    /// A date this device has never synced is absent information: it reads "Not synced", never the
    /// Scheduled dash and never an error. This is the state most likely to be forgotten by a Swift
    /// `if` chain, because it has no `WorkingState` counterpart at all.
    func testUnsyncedDateReadsAsNotSynced() {
        XCTAssertEqual(token(.unknown), "common_status_unknown")
    }

    /// Only a firing carries a punctuality axis, so "done" splits in two — the plain `common_status_done`
    /// is the dated-Task word and must never appear for a firing.
    func testDoneSplitsOnPunctuality() {
        XCTAssertEqual(token(.doneOnTime), "common_status_done_on_time")
        XCTAssertEqual(token(.doneLate), "common_status_done_late")
    }

    func testUnresolvedAndSkippedFiringsReadTheirOwnWords() {
        XCTAssertEqual(token(.scheduled), "common_status_scheduled")
        XCTAssertEqual(token(.inProgress), "common_status_in_progress")
        XCTAssertEqual(token(.skipped), "common_status_skipped")
    }

    /// A row with no reading is not a firing — a one-off dated Task, an unresolved-kind row, a synced
    /// external event — and renders from its own working state, which is a genuine fact about *that
    /// item*. That fallback is a semantic rule from ADR-0053, so it is pinned here rather than left to
    /// each View.
    func testDatedTaskFallsBackToItsOwnWorkingState() {
        let open = firing(nil, status: .open, seriesId: nil, kind: .task)
        let done = firing(nil, status: .done, seriesId: nil, kind: .task)
        XCTAssertEqual(ShellBridgeKt.occurrenceStatusToken(firing: open), "common_status_scheduled")
        XCTAssertEqual(ShellBridgeKt.occurrenceStatusToken(firing: done), "common_status_done")
        XCTAssertTrue(ShellBridgeKt.occurrenceStatusIsDone(firing: done))
        XCTAssertFalse(ShellBridgeKt.occurrenceStatusIsDone(firing: open))
    }

    /// The tint says "this happened", so it ignores punctuality — and deliberately stays neutral for
    /// `Missed` (stated in words, never coloured at) and for `Unknown` (tinting absent information would
    /// assert something this device does not know).
    func testOnlyAFiringThatHappenedTakesTheSuccessTint() {
        for state in [OccurrenceState.doneOnTime, .doneLate] {
            XCTAssertTrue(ShellBridgeKt.occurrenceStatusIsDone(firing: firing(state)), "\(state) happened")
        }
        for state in [OccurrenceState.scheduled, .inProgress, .skipped, .missed, .unknown] {
            XCTAssertFalse(ShellBridgeKt.occurrenceStatusIsDone(firing: firing(state)), "\(state) did not")
        }
    }

    /// The regression guard, and the reason this file exists: iterate **every** reading SKIE bridges
    /// (`CaseIterable`, so a state added in Kotlin lands here without this file being touched) and assert
    /// each one speaks the shared `common_status_*` vocabulary rather than the Tasks words the old
    /// `WorkingState.label` catch-all would have produced.
    func testNoReadingFallsThroughToTheTasksVocabulary() {
        for state in OccurrenceState.allCases {
            let key = token(state)
            XCTAssertTrue(key.hasPrefix("common_status_"), "\(state) mapped to \(key), not calendar vocabulary")
            XCTAssertNotEqual(key, "tasks_menu_open", "\(state) fell through to the Tasks vocabulary")
            XCTAssertNotEqual(key, "tasks_set_aside", "\(state) fell through to the Tasks vocabulary")
            XCTAssertNotEqual(key, "calendar_action_done", "\(state) reads the imperative Done ACTION")
            XCTAssertNotEqual(key, "common_status_done", "a firing splits done on punctuality")
        }
    }
}

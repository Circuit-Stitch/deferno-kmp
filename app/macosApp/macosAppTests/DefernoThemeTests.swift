import SwiftUI
import XCTest
import Deferno
@testable import macosApp

/// Locks down the `ThemeMode` → scene `preferredColorScheme` mapping (`DefernoTheme.swift`).
/// The load-bearing case is Auto → `nil`: a concrete scheme pins the scene and feeds back into the
/// `\.colorScheme` environment read inside `DefernoThemeModifier`, latching the launch-time
/// appearance so "Follow system" never tracks an OS appearance change.
///
/// The macOS port of `iosAppTests/DefernoThemeTests.swift`. ADR-0028 sanctions per-platform View-body
/// divergence, but this mapping is *not* a place to diverge — macOS shipped the exact bug iOS fixed in
/// cacc8bce because nothing on this side held the line (there was no macOS CI at all until now).
final class DefernoThemeTests: XCTestCase {

    func testLightModePinsTheSceneLight() {
        XCTAssertEqual(ThemeMode.light.preferredColorScheme, .light)
    }

    func testDarkModePinsTheSceneDark() {
        XCTAssertEqual(ThemeMode.dark.preferredColorScheme, .dark)
    }

    /// The regression: Auto must not pin a concrete scheme — `nil` keeps the scene on the OS appearance.
    func testAutoModeDoesNotPinTheScene() {
        XCTAssertNil(ThemeMode.auto.preferredColorScheme)
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
/// It lives in this file rather than its own because a new `.swift` would need a `project.yml`
/// regeneration here and a hand-edited `project.pbxproj` on iOS. The iOS twin sits in
/// `iosAppTests/StateBridgeTests.swift` for the same reason — the two are byte-for-byte, keep them so.
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

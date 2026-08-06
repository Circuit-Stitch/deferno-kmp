import Deferno
import XCTest
@testable import macosApp

/// The **TODAY cell** on the recurring-definition detail (#383, ADR-0053 decision 4) — the one reading on
/// that pane where a rendering shortcut states something untrue.
///
/// The mapping lives in one exhaustive Kotlin `when` (`BridgeKt.todayCell`) precisely so a Swift `if` chain
/// can't collapse two of the four answers. These assertions are what pins it from the platform that renders
/// it: `DefinitionDetailView` has no branch of its own, so if the `when` is wrong the pane is wrong, and
/// nothing else on the Apple side would notice.
///
/// The reading joins two orthogonal questions — *does anything fire today?* (`DayFiring`, from the offline
/// expander) and *how did today go?* (`OccurrenceState`, from the stored fact + coverage). Three of the four
/// arms below have no word in the status vocabulary at all, which is exactly why they are easy to lose.
final class TodayCellTests: XCTestCase {

    /// A firing landing on the day. The clock is irrelevant to the reading, so slot and start are the same
    /// wall time and only `isCancelled` varies.
    private func fires(cancelled: Bool) -> DayFiring {
        let at = LocalDateTime(year: 2026, month: 8, day: 5, hour: 9, minute: 0, second: 0, nanosecond: 0)
        return DayFiringFires(
            firing: Firing(recurrenceId: at, startLocal: at, isCancelled: cancelled, isOverride: false)
        )
    }

    private func cell(
        _ firing: DayFiring,
        _ state: OccurrenceState,
        stored: Bool = true
    ) -> TodayCell {
        BridgeKt.todayCell(
            today: TodayOccurrence(firing: firing, state: state, isStoredResolution: stored)
        )
    }

    /// The grid **was** reproduced and puts nothing on today — the only case that may say "not scheduled".
    func testAReproducedGridWithNoFiringSaysNotScheduled() {
        let reading = cell(DayFiringNotFiring.shared, .unknown)
        XCTAssertEqual(reading.labelKey, "tasks_detail_today_not_firing")
        XCTAssertFalse(reading.isStatus, "a statement about the schedule must not render as a status chip")
    }

    /// A cancelled firing is present-and-flagged, not absent: the slot existed and was called off, which is
    /// a different statement from the rule never having fired.
    func testACancelledFiringReadsAsCancelledRatherThanUnscheduled() {
        let reading = cell(fires(cancelled: true), .scheduled)
        XCTAssertEqual(reading.labelKey, "tasks_detail_today_cancelled")
        XCTAssertNotEqual(reading.labelKey, "tasks_detail_today_not_firing")
        XCTAssertFalse(reading.isStatus)
    }

    /// A firing that landed and was not called off reads the ordinary status vocabulary — the shared
    /// `common_status_*` words the calendar chip uses, not a fourth set.
    func testALiveFiringReadsTheSharedStatusVocabulary() {
        XCTAssertEqual(cell(fires(cancelled: false), .doneOnTime).labelKey, "common_status_done_on_time")
        XCTAssertEqual(cell(fires(cancelled: false), .missed).labelKey, "common_status_missed")
        XCTAssertTrue(cell(fires(cancelled: false), .scheduled).isStatus)
    }

    /// **The regression this file exists for.** A grid this device cannot reproduce — a `Custom` rule, an
    /// unresolvable anchor, or a backend-elided series block — is not a grid that says nothing fires.
    /// Rendering "Not scheduled today" there would state a fact we do not have.
    func testAnUnreproducibleGridNeverClaimsNothingIsScheduled() {
        for state in OccurrenceState.allCases {
            let reading = cell(DayFiringUnavailable.shared, state)
            XCTAssertNotEqual(
                reading.labelKey, "tasks_detail_today_not_firing",
                "an Unavailable grid with state \(state) claimed the day is unscheduled"
            )
        }
    }

    /// Unavailable **and** nothing synced is the honest "this device cannot say" arm.
    func testAnUnreproducibleGridWithNothingSyncedSaysSo() {
        let reading = cell(DayFiringUnavailable.shared, .unknown, stored: false)
        XCTAssertEqual(reading.labelKey, "tasks_detail_today_unavailable")
        XCTAssertFalse(reading.isStatus)
    }

    /// …but an unreproducible grid that *did* sync a resolution still reads it. We cannot say whether the
    /// rule fires today; we do know how the day resolved, and staying silent would throw that away.
    func testAnUnreproducibleGridStillReadsASyncedResolution() {
        let reading = cell(DayFiringUnavailable.shared, .doneLate)
        XCTAssertEqual(reading.labelKey, "common_status_done_late")
        XCTAssertTrue(reading.isStatus)
    }

    /// **The regression this arm actually shipped.** The guard used to test `state == .unknown`, but a
    /// *derived* `Scheduled` is not `.unknown`: every successful hydrate records coverage for the day
    /// (the server always answers), and a covered day with no stored record derives `Scheduled` from
    /// nothing but "today has not passed". So an unexpandable grid rendered the confident "Scheduled"
    /// chip — the same lie as "not scheduled today", with the sign flipped, and the one this file's
    /// sibling test could not catch because it only asserts the label is not *not_firing*.
    func testAnUnreproducibleGridNeverClaimsSomethingIsScheduled() {
        let reading = cell(DayFiringUnavailable.shared, .scheduled, stored: false)
        XCTAssertEqual(reading.labelKey, "tasks_detail_today_unavailable")
        XCTAssertFalse(reading.isStatus)
    }

    /// The provenance split the arm now turns on: the identical `.scheduled` value reads as a status
    /// when a resolution really was stored (a `?scope=this` reschedule writes exactly such a row), and
    /// as "not available" when it was merely derived.
    func testADerivedScheduledAndAStoredOneReadDifferently() {
        XCTAssertEqual(cell(DayFiringUnavailable.shared, .scheduled, stored: true).labelKey,
                       "common_status_scheduled")
        XCTAssertEqual(cell(DayFiringUnavailable.shared, .scheduled, stored: false).labelKey,
                       "tasks_detail_today_unavailable")
    }

    /// No derived state may reach the status chip on an unexpandable grid — the general form of the two
    /// above, so a new `OccurrenceState` cannot quietly reopen this.
    func testNoDerivedStateEverRendersAsAStatusOnAnUnreproducibleGrid() {
        for state in OccurrenceState.allCases {
            let reading = cell(DayFiringUnavailable.shared, state, stored: false)
            XCTAssertEqual(
                reading.labelKey, "tasks_detail_today_unavailable",
                "an Unavailable grid with derived state \(state) claimed to know the day"
            )
            XCTAssertFalse(reading.isStatus)
        }
    }

    /// What an unopened definition reads as, through the shared model's own constant rather than a
    /// hand-built pair — so a change to `TodayOccurrence.Unknown` lands here.
    func testTheUnknownReadingIsTheUnavailableArm() {
        // `Unknown`, capital U: Kotlin/Native keeps a companion `val`'s own casing in the exported header.
        XCTAssertEqual(BridgeKt.todayCell(today: TodayOccurrence.companion.Unknown).labelKey,
                       "tasks_detail_today_unavailable")
    }

    /// The tint says "this happened", so it ignores punctuality — and stays neutral for everything else,
    /// including the three grid arms, which are not outcomes at all.
    func testOnlyAResolvedFiringTakesTheSuccessTint() {
        XCTAssertTrue(cell(fires(cancelled: false), .doneOnTime).isDone)
        XCTAssertTrue(cell(fires(cancelled: false), .doneLate).isDone)
        XCTAssertFalse(cell(fires(cancelled: false), .missed).isDone)
        XCTAssertFalse(cell(DayFiringNotFiring.shared, .unknown).isDone)
        XCTAssertFalse(cell(fires(cancelled: true), .doneOnTime).isDone,
                       "a cancelled slot has no outcome to celebrate")
    }

    /// Every arm resolves to a key the catalog actually carries. `L.string` echoes its key when the lookup
    /// misses, so a typo'd key would ship to a user as a raw identifier.
    func testEveryArmResolvesToARealCatalogEntry() {
        var readings = [cell(DayFiringNotFiring.shared, .unknown),
                        cell(DayFiringUnavailable.shared, .unknown),
                        cell(fires(cancelled: true), .scheduled)]
        readings += OccurrenceState.allCases.map { cell(fires(cancelled: false), $0) }
        for reading in readings {
            XCTAssertNotEqual(L.string(reading.labelKey), reading.labelKey,
                              "\(reading.labelKey) has no catalog entry")
        }
    }
}

/// The kind-carrying item token (#383) — the codec behind SwiftUI view identity and the detached window's
/// `WindowGroup` scene payload.
///
/// It replaced a bare id that the window opener re-wrapped as a `TaskId`, which is how a Habit in a detached
/// window ended up on a Task-typed path (`GET /tasks/{habitId}` → a 404 the outbox posture reads as
/// success). The round-trip below is the property that fix depends on.
final class ItemRefTokenTests: XCTestCase {

    func testEveryKindRoundTrips() {
        for kind in ItemKind.allCases {
            let ref = ItemRef(id: "1f0a-uuid", kind: kind)
            let decoded = BridgeKt.itemRefFromToken(token: BridgeKt.itemRefToken(ref: ref))
            XCTAssertEqual(decoded?.id, ref.id)
            XCTAssertEqual(decoded?.kind, kind, "\(kind) did not survive the token")
        }
    }

    /// A recurring token must never decode to something a `TaskId` can be minted from — that conversion is
    /// the guard `ItemRef.taskId` exists to provide, and the token is upstream of it.
    func testARecurringTokenDecodesToARefWithNoTaskId() {
        let token = BridgeKt.itemRefToken(ref: ItemRef(id: "h-1", kind: .habit))
        guard let decoded = BridgeKt.itemRefFromToken(token: token) else {
            return XCTFail("a Habit token did not decode")
        }
        // Bound, not optional-chained: `taskId` is itself optional, and `decoded?.taskId` would hand
        // XCTAssertNil a `.some(.none)` that passes for the wrong reason.
        XCTAssertNil(decoded.taskId)
        XCTAssertTrue(decoded.isDefinition)
    }

    /// A payload with no kind is a Task id — a fact, not a guess: macOS restores `WindowGroup` scenes across
    /// launches, and every payload an older build wrote came from a Task-gated opener. Refusing them would
    /// dismiss a restored Task window for no reason.
    func testALegacyBareIdIsReadAsATaskId() {
        let decoded = BridgeKt.itemRefFromToken(token: "b3a1-uuid")
        XCTAssertEqual(decoded?.kind, .task)
        XCTAssertEqual(decoded?.id, "b3a1-uuid")
    }

    /// A kind this build has never heard of returns nil (the window closes itself). Falling back to Task
    /// there is precisely how the bug would come back.
    func testAnUnknownKindIsRefusedRatherThanGuessed() {
        XCTAssertNil(BridgeKt.itemRefFromToken(token: "Ritual:x-1"))
        XCTAssertNil(BridgeKt.itemRefFromToken(token: ""))
        XCTAssertNil(BridgeKt.itemRefFromToken(token: "Habit:"))
    }
}

/// The [[Definition state]] light switch, as the detail's STATUS row reads it. It is emphatically not a
/// `WorkingState` — a Habit/Chore/Event is never "done" — so the failure mode this guards is the pane
/// speaking the Tasks vocabulary at a definition, or rendering a raw enum name.
final class DefinitionStateTokenTests: XCTestCase {

    func testEveryStateMapsToItsOwnCatalogEntry() {
        let keys = DefinitionState.allCases.map { BridgeKt.definitionStateToken(state: $0) }
        XCTAssertEqual(Set(keys).count, keys.count, "two definition states share a word: \(keys)")
        for key in keys {
            XCTAssertNotEqual(L.string(key), key, "\(key) has no catalog entry")
        }
    }

    /// In review is the one value the two axes genuinely share, and it reads the shared word rather than a
    /// third key that says the same thing.
    func testInReviewReadsTheSharedStatusWord() {
        XCTAssertEqual(BridgeKt.definitionStateToken(state: .inReview), "common_status_in_review")
    }

    func testActiveAndArchivedReadTheDefinitionNouns() {
        XCTAssertEqual(BridgeKt.definitionStateToken(state: .active), "tasks_definition_state_active")
        XCTAssertEqual(BridgeKt.definitionStateToken(state: .archived), "tasks_definition_state_archived")
    }
}

/// The tree row's recurrence **cursor** clause (#384), and the one place #383's `cursorDay` split could
/// change what an existing surface says.
///
/// `cursor` wraps its day in `tasks_recurrence_next_due` ("Next: %@"); `cursorDay` returns the day bare
/// for the detail's NEXT DUE row, whose label already says "next". EXHAUSTED is the value that is not a
/// day at all — it is a whole clause — so only one of the two may wrap it.
final class RecurrenceCursorClauseTests: XCTestCase {

    private func tokens(cursor: String?, count: Int? = nil) -> RecurrenceLineTokens {
        RecurrenceLineTokens(
            cadence: "WEEKLY",
            cadenceCount: nil,
            weekdays: [1],
            bound: nil,
            boundCount: nil,
            boundEpochDays: nil,
            cursor: cursor,
            cursorCount: count.map { KotlinInt(int: Int32($0)) }
        )
    }

    /// **The regression.** Splitting `cursorDay` out left the EXHAUSTED guard below the wrapper, so an
    /// ended series' tree-row subtitle read "Next: Series ended" — which contradicts itself — in all five
    /// locales. The iOS twin kept its guard, so this was macOS-only and no shared test could see it.
    func testAnEndedSeriesIsNotWrappedInTheNextDuePhrase() {
        let line = L.cursor(tokens(cursor: "EXHAUSTED"))
        XCTAssertEqual(line, L.string("tasks_recurrence_series_ended"))
        XCTAssertNotEqual(line, L.format("tasks_recurrence_next_due", L.string("tasks_recurrence_series_ended")))
    }

    /// A real day still takes the wrapper — the split must not have cost the tree row its "Next:".
    func testARealCursorDayStillTakesTheNextDueWrapper() {
        XCTAssertEqual(
            L.cursor(tokens(cursor: "TOMORROW")),
            L.format("tasks_recurrence_next_due", L.string("tasks_detail_due_tomorrow"))
        )
    }

    /// …and the bare form never wraps, which is the whole reason it exists.
    func testTheBareFormIsUnwrappedForBothArms() {
        XCTAssertEqual(L.cursorDay(tokens(cursor: "EXHAUSTED")), L.string("tasks_recurrence_series_ended"))
        XCTAssertEqual(L.cursorDay(tokens(cursor: "TOMORROW")), L.string("tasks_detail_due_tomorrow"))
    }

    /// No cursor at all — a Task, or an Archived definition whose stale cursor the reading refuses.
    func testNoCursorRendersNothingEitherWay() {
        XCTAssertNil(L.cursor(tokens(cursor: nil)))
        XCTAssertNil(L.cursorDay(tokens(cursor: nil)))
    }
}

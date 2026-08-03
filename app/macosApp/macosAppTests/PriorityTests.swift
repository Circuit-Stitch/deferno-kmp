import Deferno
import XCTest
@testable import macosApp

/// Coverage for the two **pure string mappings** the #375 priority surfaces hang off — the ones a compiler
/// cannot check for you.
///
/// Both are `switch`es that fall through to a default arm rather than being exhaustive over a Kotlin enum:
/// `Priority.label` matches SKIE-bridged cases by value equality (a bridged enum can't be `switch`ed in a
/// static framework, see `WorkingState.label`) and `searchSortLabel` switches over a raw String key. In both
/// the failure mode is silent and identical — a new value ships to a user as its RAW enum name — so these
/// tests exist to be the exhaustiveness check the language won't give.
///
/// Deliberately no `Task`-shaped tests here: `Task.id` is a Kotlin value class that Kotlin/Native erases, so
/// a `Task` cannot be constructed from Swift, and the row/candidate logic that consumes one is exercised by
/// the shared `feature/tasks` + `feature/plan` suites instead.
final class PriorityTests: XCTestCase {

    /// Rank order is the *product* order: Fire above Normal above Backlog, matching `Priority.bucketRank`
    /// (whose Kotlin doc pins the ordinal as the rank). The picker offers the buckets in this order, so a
    /// silent reordering would present "most urgent last".
    func testOrderedIsRankOrderMostUrgentFirst() {
        XCTAssertEqual(Priority.ordered, [.fire, .normal, .backlog])
        XCTAssertEqual(Priority.ordered.map { Int($0.bucketRank) }, [0, 1, 2])
    }

    /// `ordered` must cover the whole enum — a bucket added to `core/model` and not added there would be
    /// unreachable from the picker, which is the only way to set priority on macOS.
    func testOrderedCoversEveryBucket() {
        XCTAssertEqual(Set(Priority.ordered), Set(Priority.allCases))
    }

    /// Every bucket maps to its own catalog key. Asserted against `L.string(...)` rather than against the
    /// English words: the two are equal by coincidence in English (`common_priority_fire` *is* "Fire", which
    /// is also the raw Kotlin enum name), so a literal expectation would pin the wrong thing and a
    /// "label != name" check would fail on an English system while passing everywhere else.
    func testEveryBucketMapsToItsOwnCatalogKey() {
        XCTAssertEqual(Priority.fire.label, L.string("common_priority_fire"))
        XCTAssertEqual(Priority.normal.label, L.string("common_priority_normal"))
        XCTAssertEqual(Priority.backlog.label, L.string("common_priority_backlog"))
    }

    /// …and no two buckets read the same, so the picker never offers the same word twice.
    func testTheThreeBucketLabelsAreDistinct() {
        let labels = Priority.ordered.map { $0.label }
        XCTAssertEqual(Set(labels).count, labels.count, "two buckets share a label: \(labels)")
    }

    /// The wire-token twin used by the Trail change diff — same words, keyed off the server's tokens.
    func testWirePriorityTokensMapToTheSameWordsAsTheRow() {
        XCTAssertEqual(L.priorityWireLabel("fire"), Priority.fire.label)
        XCTAssertEqual(L.priorityWireLabel("normal"), Priority.normal.label)
        XCTAssertEqual(L.priorityWireLabel("backlog"), Priority.backlog.label)
    }

    /// A token this client doesn't know degrades to itself (the tolerant-reader posture the rest of the
    /// diff formatting takes) rather than rendering an empty cell.
    func testAnUnknownWirePriorityTokenDegradesToItself() {
        XCTAssertEqual(L.priorityWireLabel("someday-maybe"), "someday-maybe")
    }

    /// The soft target date is its own Trail field — never relabelled as the hard deadline (#375).
    func testTargetDateDiffFieldIsNotLabelledAsADeadline() {
        XCTAssertNotEqual(L.diffFieldLabel("TARGET_DATE"), L.diffFieldLabel("DEADLINE"))
        XCTAssertNotEqual(L.diffFieldLabel("TARGET_DATE"), "TARGET_DATE")
        XCTAssertNotEqual(L.diffFieldLabel("PRIORITY"), "PRIORITY")
    }
}

/// The Search sort chips. `searchSortLabel`'s `default:` arm renders the raw enum name, so the entry point
/// that matters is the walk over every `SearchSort` the shared module actually offers.
final class SearchSortLabelTests: XCTestCase {

    /// The regression this file was written for: `PriorityRank` shipped in `core/data`'s `SearchSort` and the
    /// macOS chip rendered "PriorityRank" until an arm was added. Walking the live entries means the *next*
    /// sort added anywhere fails here instead of reaching a user untranslated.
    func testEverySearchSortHasALabelArm() {
        let sorts = ShellBridgeKt.searchSortValues()
        XCTAssertFalse(sorts.isEmpty, "no SearchSort entries reached Swift — the bridge seam is broken")
        for sort in sorts {
            let key = ShellBridgeKt.searchSortKey(sort: sort)
            XCTAssertNotEqual(
                searchSortLabel(key), key,
                "SearchSort.\(key) has no arm in searchSortLabel — its chip renders the raw enum name"
            )
        }
    }

    /// The ranked sort reads as "Priority" — not as a deadline sort, which is the neighbouring chip.
    func testTheRankedSortReadsAsPriority() {
        XCTAssertEqual(searchSortLabel("PriorityRank"), L.string("search_sort_priority"))
        XCTAssertNotEqual(searchSortLabel("PriorityRank"), searchSortLabel("DeadlineAsc"))
    }
}

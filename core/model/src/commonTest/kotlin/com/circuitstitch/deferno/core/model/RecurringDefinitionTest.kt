package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.time.Instant

/**
 * Contract for [RecurringDefinition] — the kind-neutral read model #383's detail renders, and the
 * [toItem] bridge back to the [Item] projection the display readings are already written against.
 *
 * Two claims are load-bearing and everything here is pointed at them:
 *
 * - **The projection is genuinely kind-neutral.** Habit, Chore and Event are unrelated types with no
 *   supertype, so the only evidence that one detail surface can serve all three is that three
 *   identically-populated definitions project to the *same value* apart from `id` and `kind`. That is
 *   asserted whole-object, so a field silently dropped from one of the three mappers fails here rather
 *   than showing up as an empty row on one kind's detail.
 * - **[toItem] exists so the readings are reused, not reimplemented.** The fifth copy of cadence
 *   normalisation is what #384 went out of its way to prevent, so the bridged [Item] is checked by
 *   feeding it to the real readings ([Item.recurrenceCursor], [dayFiring]) rather than only by
 *   comparing fields.
 *
 * `today` and the zone are pinned; a test that reached for a clock would rot.
 */
class RecurringDefinitionTest {

    private val today = LocalDate(2026, 6, 15)
    private val zone = TimeZone.UTC
    private val created = Instant.parse("2026-05-04T01:53:05.597388900Z")

    /** The walked cursor — tomorrow, so a live series reads as one. NOT a bound; the bound is on the rule. */
    private val cursor = Instant.parse("2026-06-16T09:00:00Z")
    private val recurrence = Recurrence(Cadence.Weekly(listOf("Mon", "Thu")), RecurrenceBound.AfterCount(20))
    private val series = SeriesInputs(
        anchorLocal = LocalDateTime.parse("2026-06-01T09:00:00"),
        tzid = "America/Los_Angeles",
    )
    private val parent = TaskId("parent-1")

    private val habit = Habit(
        id = HabitId("def-1"),
        orgSlug = "u-e4h2qk",
        title = "stretch",
        definitionState = DefinitionState.Active,
        recurrence = recurrence,
        labels = listOf("health", "morning"),
        parentId = parent,
        completeBy = cursor,
        ref = "u-e4h2qk-42",
        dateCreated = created,
        hydration = HydrationState.Full,
        description = "shoulders and hips",
        seriesId = "series-9",
        series = series,
        blocked = true,
        isBlocker = true,
    )

    private val chore = Chore(
        id = ChoreId("def-1"),
        orgSlug = "u-e4h2qk",
        title = "stretch",
        definitionState = DefinitionState.Active,
        recurrence = recurrence,
        cadenceMode = CadenceMode.Fixed,
        labels = listOf("health", "morning"),
        parentId = parent,
        completeBy = cursor,
        ref = "u-e4h2qk-42",
        dateCreated = created,
        hydration = HydrationState.Full,
        description = "shoulders and hips",
        seriesId = "series-9",
        series = series,
        blocked = true,
        isBlocker = true,
    )

    private val event = Event(
        id = EventId("def-1"),
        orgSlug = "u-e4h2qk",
        title = "stretch",
        definitionState = DefinitionState.Active,
        recurrence = recurrence,
        completeBy = cursor,
        endTime = Instant.parse("2026-06-16T10:00:00Z"),
        labels = listOf("health", "morning"),
        parentId = parent,
        ref = "u-e4h2qk-42",
        dateCreated = created,
        hydration = HydrationState.Full,
        description = "shoulders and hips",
        seriesId = "series-9",
        series = series,
        blocked = true,
        isBlocker = true,
    )

    /** The projection all three must produce, `kind` aside. Spelled out so a dropped field cannot pass. */
    private val expected = RecurringDefinition(
        id = "def-1",
        kind = ItemKind.Habit,
        title = "stretch",
        definitionState = DefinitionState.Active,
        description = "shoulders and hips",
        labels = listOf("health", "morning"),
        recurrence = recurrence,
        cursorAt = cursor,
        seriesId = "series-9",
        series = series,
        parentId = parent,
        ref = "u-e4h2qk-42",
        hydration = HydrationState.Full,
        blocked = true,
        isBlocker = true,
    )

    // ── The three mappers ────────────────────────────────────────────────────────────────────

    @Test
    fun aHabitProjectsEveryFieldTheDetailRenders() {
        assertEquals(expected, habit.toDefinition())
    }

    @Test
    fun aChoreProjectsEveryFieldTheDetailRenders() {
        assertEquals(expected.copy(kind = ItemKind.Chore), chore.toDefinition())
    }

    @Test
    fun anEventProjectsEveryFieldTheDetailRenders() {
        assertEquals(expected.copy(kind = ItemKind.Event), event.toDefinition())
    }

    /**
     * The kind-neutrality claim itself: three identically-populated definitions of three unrelated
     * types differ **only** in their kind after projection. This is what lets the detail drop its
     * `when (kind)` — and it is asserted directly rather than inferred from the three cases above.
     */
    @Test
    fun theThreeKindsProjectToTheSameValueApartFromTheKind() {
        val projections = listOf(habit.toDefinition(), chore.toDefinition(), event.toDefinition())
        assertEquals(listOf(ItemKind.Habit, ItemKind.Chore, ItemKind.Event), projections.map { it.kind })
        assertEquals(1, projections.map { it.copy(kind = ItemKind.Habit) }.toSet().size)
    }

    /**
     * The rename is the whole point of the field: on a recurring definition `complete_by` is the
     * [[Recurrence cursor]] — where the series has *walked to* — and reading it as a deadline is the
     * mis-read the recurring epic keeps tripping over. The bound lives on the rule instead, and the two
     * are carried side by side here so a reader can see they are different values.
     */
    @Test
    fun theWiresCompleteByLandsOnCursorAtAndTheBoundStaysOnTheRule() {
        assertEquals(habit.completeBy, habit.toDefinition().cursorAt)
        assertEquals(chore.completeBy, chore.toDefinition().cursorAt)
        assertEquals(event.completeBy, event.toDefinition().cursorAt)
        assertEquals(RecurrenceBound.AfterCount(20), habit.toDefinition().recurrence?.bound)
    }

    /**
     * The elision travels intact. A `null` [SeriesInputs] means "this device cannot reproduce that
     * grid", never "that grid has no exclusions", so a mapper that defaulted it to an empty
     * `SeriesInputs` would manufacture a schedule — see [SeriesInputs]'s KDoc.
     */
    @Test
    fun anElidedSeriesBlockStaysNullRatherThanBecomingAnEmptyOne() {
        assertEquals(null, habit.copy(series = null).toDefinition().series)
        assertEquals(null, chore.copy(series = null).toDefinition().series)
        assertEquals(null, event.copy(series = null).toDefinition().series)
    }

    @Test
    fun anUnhydratedSummaryProjectsAsSummary() {
        // Hydration is carried so the detail can tell "no description" from "not fetched yet".
        val summary = habit.copy(hydration = HydrationState.Summary, description = null)
        assertEquals(HydrationState.Summary, summary.toDefinition().hydration)
        assertEquals(null, summary.toDefinition().description)
    }

    @Test
    fun aDefinitionsOwnRefCarriesItsKind() {
        assertEquals(ItemRef("def-1", ItemKind.Habit), habit.toDefinition().itemRef)
        assertEquals(ItemRef("def-1", ItemKind.Chore), chore.toDefinition().itemRef)
        assertEquals(ItemRef("def-1", ItemKind.Event), event.toDefinition().itemRef)
    }

    // ── toItem: the bridge back to the shared readings ───────────────────────────────────────

    @Test
    fun theBridgedItemCarriesTheStructureAndTheRecurringPair() {
        assertEquals(
            Item(
                id = "def-1",
                kind = ItemKind.Habit,
                title = "stretch",
                parentId = "parent-1",
                isTerminal = false,
                blocked = true,
                isBlocker = true,
                definitionState = DefinitionState.Active,
                recurrence = recurrence,
                recurrenceCursorAt = cursor,
                seriesId = "series-9",
                series = series,
            ),
            habit.toDefinition().toItem(),
        )
    }

    /**
     * The tree-only fields the detail cannot know — the subtree counts, the dependency edge list, the
     * external provenance, the sibling order — stay at their defaults rather than being invented. A
     * fabricated `descendantTotal` of 0 would render a progress badge claiming a childless item.
     */
    @Test
    fun theTreeOnlyFieldsAreLeftAtTheirDefaultsRatherThanInvented() {
        val item = habit.toDefinition().toItem()
        assertEquals(null, item.descendantDone)
        assertEquals(null, item.descendantTotal)
        assertEquals(emptyList(), item.blockedBy)
        assertEquals(null, item.source)
        assertEquals(null, item.externalRef)
        assertEquals(null, item.sequence)
    }

    /**
     * `isTerminal` is the tree's de-emphasis signal, and for a recurring row exactly one state earns it:
     * Archived. `InReview` is retained faithfully pending a backend clarification (ADR-0011) and is
     * **not** terminal — asserted over `entries` so a fourth state cannot slip in un-answered.
     */
    @Test
    fun onlyAnArchivedDefinitionBridgesAsTerminal() {
        assertEquals(
            setOf(DefinitionState.Archived),
            DefinitionState.entries
                .filter { habit.copy(definitionState = it).toDefinition().toItem().isTerminal }
                .toSet(),
        )
    }

    @Test
    fun theFullDefinitionStateSurvivesTheBridgeNotJustTheTerminalFlag() {
        // [Item.isTerminal] collapses three states to a boolean; the light switch itself is carried so
        // the shared readings (and the tree's Archive/Activate menu) can still see which one it is.
        for (state in DefinitionState.entries) {
            assertEquals(state, habit.copy(definitionState = state).toDefinition().toItem().definitionState)
        }
    }

    /**
     * The reuse claim, exercised rather than asserted: the bridged [Item] is fed to the real cursor
     * reading and gives the reading it should. A cursor one day out reads Tomorrow — and the Archived
     * arm still reads NoCursor through the bridge, because the server leaves a stale cursor on archive.
     */
    @Test
    fun theBridgedItemFeedsTheSharedCursorReading() {
        assertEquals(
            RecurrenceCursor.DueOn(RelativeDay.Tomorrow),
            habit.toDefinition().toItem().recurrenceCursor(zone, today),
        )
        assertEquals(
            RecurrenceCursor.NoCursor,
            habit.copy(definitionState = DefinitionState.Archived).toDefinition().toItem()
                .recurrenceCursor(zone, today),
        )
        assertEquals(
            RecurrenceCursor.Exhausted,
            habit.copy(completeBy = null).toDefinition().toItem().recurrenceCursor(zone, today),
        )
    }

    /**
     * The other half of the reuse claim: the rule **and** its expansion inputs both survive, so the
     * bridged row reaches a real [[Occurrence grid]] offline. `today` is a Monday and the rule fires
     * Mon/Thu, so this is a firing the grid genuinely produces rather than a shape check.
     */
    @Test
    fun theBridgedItemStillReachesTheOfflineGrid() {
        val item = habit.toDefinition().toItem()
        assertIs<DayFiring.Fires>(dayFiring(item.recurrence, item.series, today))
        // And the elision still refuses rather than reading as "nothing fires today".
        val elided = habit.copy(series = null).toDefinition().toItem()
        assertEquals(DayFiring.Unavailable, dayFiring(elided.recurrence, elided.series, today))
    }

    @Test
    fun aRootDefinitionBridgesWithNoParent() {
        assertEquals(null, habit.copy(parentId = null).toDefinition().toItem().parentId)
        // A parented one unwraps the typed id to the raw string the forest compares tree edges on.
        assertEquals("parent-1", habit.toDefinition().toItem().parentId)
    }
}

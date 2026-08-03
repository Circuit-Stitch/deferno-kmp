package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for the recurring-definition domain projections (Habit / Chore / Event) and the
 * [Occurrence]: the tombstone read helper, the summary defaults, and — the load-bearing glossary
 * invariant — that a *definition* carries a [DefinitionState] while an *Occurrence* carries an
 * [OccurrenceState], two distinct types that can never be confused (ADR-0011, #71).
 */
class RecurringItemTest {

    private val created = Instant.parse("2026-05-04T01:53:05.597388900Z")

    @Test
    fun habitDefaultsToUnhydratedSummaryAndReadsItsTombstone() {
        val habit = Habit(
            id = HabitId("h-1"),
            orgSlug = "u-e4h2qk",
            title = "stretch",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Daily),
            dateCreated = created,
        )
        assertEquals(HydrationState.Summary, habit.hydration)
        assertFalse(habit.isDeleted)
        assertEquals(DefinitionState.Active, habit.definitionState)
        assertTrue(habit.copy(deletedAt = created).isDeleted)
    }

    @Test
    fun choreCarriesCadenceModeAndRecurrenceButNoGroup() {
        val chore = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Weekly(listOf("Tue"))),
            cadenceMode = "rolling",
            dateCreated = created,
        )
        // The chore's `cadenceMode` and its recurrence's [Cadence] are unrelated despite the names.
        assertEquals("rolling", chore.cadenceMode)
        assertEquals(Cadence.Weekly(listOf("Tue")), chore.recurrence?.cadence)
        // Deferred (ADR-0015): no group/rotation field exists on the model at all.
    }

    @Test
    fun eventCarriesItsFixedWindow() {
        val event = Event(
            id = EventId("e-1"),
            orgSlug = "u-e4h2qk",
            title = "standup",
            definitionState = DefinitionState.Active,
            completeBy = Instant.parse("2026-04-18T16:00:00Z"),
            endTime = Instant.parse("2026-04-18T17:30:00Z"),
            allDay = false,
            dateCreated = created,
        )
        assertEquals(Instant.parse("2026-04-18T16:00:00Z"), event.completeBy)
        assertEquals(Instant.parse("2026-04-18T17:30:00Z"), event.endTime)
        assertFalse(event.allDay)
    }

    /**
     * The soft target date + urgency bucket ride **all four kinds**, not just Task (#375) — the
     * server carries them wherever `pinned`/`complete_by` already live, so restricting them to Task
     * would be the special case. Series-level, like `complete_by`: there is no per-occurrence target.
     */
    @Test
    fun everyRecurringDefinitionCarriesTargetDateAndPriority() {
        val want = Instant.parse("2026-05-10T00:00:00Z")

        val habit = Habit(
            id = HabitId("h-1"),
            orgSlug = "u-e4h2qk",
            title = "stretch",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Daily),
            dateCreated = created,
        )
        assertEquals(null, habit.targetDate)
        assertEquals(Priority.Normal, habit.priority)
        assertEquals(want, habit.copy(targetDate = want).targetDate)
        assertEquals(Priority.Fire, habit.copy(priority = Priority.Fire).priority)

        val chore = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Active,
            dateCreated = created,
        )
        assertEquals(null, chore.targetDate)
        assertEquals(Priority.Normal, chore.priority)
        assertEquals(Priority.Backlog, chore.copy(priority = Priority.Backlog).priority)

        val event = Event(
            id = EventId("e-1"),
            orgSlug = "u-e4h2qk",
            title = "standup",
            definitionState = DefinitionState.Active,
            dateCreated = created,
        )
        assertEquals(null, event.targetDate)
        assertEquals(Priority.Normal, event.priority)
        assertEquals(want, event.copy(targetDate = want).targetDate)
    }

    @Test
    fun occurrenceIsDistinctFromItsDefinitionAndUsesOccurrenceState() {
        val occurrence = Occurrence(
            id = OccurrenceId("o-1"),
            definitionId = "h-1",
            kind = ItemKind.Habit,
            date = LocalDate(2026, 5, 4),
            state = OccurrenceState.Scheduled,
        )
        assertEquals(ItemKind.Habit, occurrence.kind)
        assertEquals("h-1", occurrence.definitionId)
        // The Occurrence's state is an OccurrenceState — never a DefinitionState.
        assertEquals(OccurrenceState.Scheduled, occurrence.state)
        assertFalse(occurrence.state.isResolved)
    }
}

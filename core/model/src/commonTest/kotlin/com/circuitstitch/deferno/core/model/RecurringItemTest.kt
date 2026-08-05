package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Contract for the recurring-definition domain projections (Habit / Chore / Event) and one dated
 * firing of them: the tombstone read helper, the summary defaults, and — the load-bearing glossary
 * invariant — that a *definition* carries a [DefinitionState] while a *firing* carries an
 * [OccurrenceResolution], two distinct types that can never be confused (ADR-0011, #71).
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
            cadenceMode = CadenceMode.Rolling,
            dateCreated = created,
        )
        // The chore's [CadenceMode] and its recurrence's [Cadence] are unrelated despite the names: one
        // says how the schedule ADVANCES after a completion, the other says which days it FIRES on.
        assertEquals(CadenceMode.Rolling, chore.cadenceMode)
        assertEquals(Cadence.Weekly(listOf("Tue")), chore.recurrence?.cadence)
        // Deferred (ADR-0015): no group/rotation field exists on the model at all.
    }

    /**
     * The field is **non-null and defaults to [CadenceMode.Rolling]** (#401), which is a claim about the
     * backend and not a convenience: `Chore.cadence_mode` is `#[serde(default)]` over a `#[default]
     * Rolling` variant, so a chore that states no mode *is* rolling. Every client-created chore is in
     * exactly that position — no client code has ever set the field — so a nullable "not stated yet"
     * third state would describe the whole local cache and mean nothing.
     */
    @Test
    fun aChoreThatStatesNoModeIsRollingRatherThanUnknown() {
        val chore = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Daily),
            dateCreated = created,
        )
        assertEquals(CadenceMode.Rolling, chore.cadenceMode)
        assertEquals(CadenceMode.Fixed, chore.copy(cadenceMode = CadenceMode.Fixed).cadenceMode)
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
    fun aFiringIsDistinctFromItsDefinitionAndCarriesAResolutionNotADefinitionState() {
        // The firing is an [OccurrenceFact] keyed by (kind, definitionId, date) — it has no id of its
        // own, because a habit occurrence has none on the wire at all (#390, ADR-0053 decision 4). The
        // domain `Occurrence` this test used to build was a read projection of an id the client could
        // never join against anything it writes, and it retired with the store that held it.
        val firing = OccurrenceFact(
            kind = ItemKind.Habit,
            definitionId = "h-1",
            date = LocalDate(2026, 5, 4),
            resolution = OccurrenceResolution.InProgress,
        )
        assertEquals(ItemKind.Habit, firing.kind)
        assertEquals("h-1", firing.definitionId)
        // The firing's progress is an OccurrenceResolution — never a DefinitionState. `InProgress` is
        // the one token both vocabularies could plausibly have shared, and they do not.
        assertEquals(OccurrenceResolution.InProgress, firing.resolution)
        assertFalse(firing.resolution.isTerminal)
        // And a resolution is not a *reading*: nothing here says Scheduled or Missed, which are
        // functions of today and are derived by `resolveOccurrenceState`, never stored.
        assertEquals(null, firing.doneAt)
    }
}

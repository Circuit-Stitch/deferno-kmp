package com.circuitstitch.deferno.feature.calendar

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.model.CalendarFiring
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

private val TODAY = LocalDate(2026, 6, 15)
private const val TZ = "America/New_York"

private fun TestScope.calendarComponent(
    repo: FakeCalendarRepository,
    editor: OccurrenceEditor = RecordingOccurrenceEditor(),
    facts: FakeOccurrenceFactStore = FakeOccurrenceFactStore(),
    coverage: FakeOccurrenceCoverageStore = FakeOccurrenceCoverageStore(),
    definitions: FakeDefinitionStateSource = FakeDefinitionStateSource(),
    output: (CalendarComponent.Output) -> Unit = {},
) = DefaultCalendarComponent(
    componentContext = DefaultComponentContext(LifecycleRegistry()),
    calendarRepository = repo,
    occurrenceEditor = editor,
    occurrenceFacts = facts,
    occurrenceCoverage = coverage,
    definitionStates = definitions,
    // A fixed today, supplied as the provider the production hosts pass: every reading below is a
    // function of it, so a clock read here would make these tests expire.
    today = { TODAY },
    tz = TZ,
    output = output,
    coroutineContext = StandardTestDispatcher(testScheduler),
)

@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class DefaultCalendarComponentTest {

    @Test
    fun opensOnTodaysMonthAndPullsTheGridWindow() = runTest {
        val repo = FakeCalendarRepository()
        val component = calendarComponent(repo)
        advanceUntilIdle()

        // The initial state opens on this month, selected = today.
        assertEquals(LocalDate(2026, 6, 1), component.state.value.visibleMonth)
        assertEquals(TODAY, component.state.value.selectedDay)
        // On open it refreshes exactly the 6-week grid window for June 2026 (derived, not hard-coded).
        val (gridStart, gridEnd) = monthGridWindow(LocalDate(2026, 6, 1))
        assertEquals(listOf(Triple(gridStart, gridEnd, TZ)), repo.refreshArgs)
    }

    @Test
    fun selectingADayShowsItsAgenda_andMarkersSurface() = runTest {
        val day = LocalDate(2026, 6, 8)
        val repo = FakeCalendarRepository()
        repo.markers.value = mapOf(day to 2)
        repo.setAgenda(day, listOf(calendarItem("ce-1", day)))
        val component = calendarComponent(repo)

        component.state.test {
            // The visible month's markers surface (await past the stateIn seed).
            var s = awaitItem()
            while (s.markers.isEmpty()) s = awaitItem()
            assertEquals(mapOf(day to 2), s.markers)

            // Selecting a day shows its agenda.
            component.onDaySelected(day)
            while (s.selectedDay != day || s.agenda.isEmpty()) s = awaitItem()
            assertEquals(listOf("ce-1"), s.agenda.map { it.item.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun pagingToThePreviousMonthRePointsAndPullsThatWindow() = runTest {
        val repo = FakeCalendarRepository()
        val component = calendarComponent(repo)
        advanceUntilIdle() // the on-open refresh of this month
        repo.refreshArgs.clear()

        component.onShowPreviousMonth()
        advanceUntilIdle()

        // The previous month's grid window is pulled. The window itself encodes the paged-to month
        // (derived from the same pure helper the SUT uses), so the assertion needs no collected state.
        val (gridStart, gridEnd) = monthGridWindow(LocalDate(2026, 5, 1))
        assertEquals(listOf(Triple(gridStart, gridEnd, TZ)), repo.refreshArgs)
    }

    @Test
    fun markRoutesToTheOccurrenceEditor() = runTest {
        val repo = FakeCalendarRepository()
        val editor = RecordingOccurrenceEditor()
        val component = calendarComponent(repo, editor)

        component.onMark("ce-1", OccurrenceAction.Complete)
        component.onReschedule("ce-2", LocalDate(2026, 6, 20))
        component.onClear("ce-3")
        advanceUntilIdle()

        assertEquals(
            listOf("mark:ce-1:Complete", "reschedule:ce-2:2026-06-20", "clear:ce-3"),
            editor.calls,
        )
    }

    @Test
    fun theFabEmitsCreateForTheSelectedDay() = runTest {
        val repo = FakeCalendarRepository()
        val outputs = mutableListOf<CalendarComponent.Output>()
        val component = calendarComponent(repo, output = { outputs += it })

        component.onDaySelected(LocalDate(2026, 6, 8))
        component.onNewForSelectedDay()

        assertEquals(
            listOf<CalendarComponent.Output>(CalendarComponent.Output.CreateForDay(LocalDate(2026, 6, 8))),
            outputs,
        )
    }

    // ---- the occurrence-state reading (ADR-0053 decision 4) -------------------------------------

    @Test
    fun aStoredResolutionIsTheReading_doneLateSurvivesVerbatim() = runTest {
        val past = LocalDate(2026, 6, 10)
        val world = firingWorld(past)
        world.facts.record(
            fact(past, OccurrenceResolution.DoneLate, doneAt = "2026-06-10T23:30:00Z", completeBy = "2026-06-10T09:00:00Z"),
        )

        // What the server recorded is what happened — the punctuality split is not re-litigated here,
        // and `today` cannot age a resolved firing into anything else.
        assertEquals(OccurrenceState.DoneLate, world.readingOn(past))
    }

    @Test
    fun aPastCoveredFiringWithNoFactOnAnActiveDefinitionReadsMissed() = runTest {
        val past = LocalDate(2026, 6, 10)
        val world = firingWorld(past, definitionState = DefinitionState.Active)

        // Inside coverage the absence of a fact is evidence: nothing was recorded, and the day is gone.
        assertEquals(OccurrenceState.Missed, world.readingOn(past))
    }

    @Test
    fun theSamePastFiringOnAnArchivedDefinitionReadsSkipped_notMissed() = runTest {
        val past = LocalDate(2026, 6, 10)
        val world = firingWorld(past, definitionState = DefinitionState.Archived)

        // A shelved definition's empty past days are history, not a reproach — archiving leaves the
        // recurrence cursor untouched, so deriving Missed here would shame someone for switching it off.
        assertEquals(OccurrenceState.Skipped, world.readingOn(past))
    }

    @Test
    fun aPastFiringOUTSIDECoverageReadsUnknown_neverMissed_becauseIgnoranceIsNotEvidence() = runTest {
        val past = LocalDate(2026, 6, 10)
        // Coverage that stops the day before, on an Active definition: every input that would produce
        // Missed is present EXCEPT the evidence that this device ever looked at that date.
        val world = firingWorld(
            past,
            coverage = OccurrenceCoverage(ItemKind.Habit, "task-ce-1", LocalDate(2026, 6, 11), LocalDate(2026, 6, 15)),
            definitionState = DefinitionState.Active,
        )

        assertEquals(OccurrenceState.Unknown, world.readingOn(past))
    }

    @Test
    fun anUncachedDefinitionReadsUnknown_notMissed() = runTest {
        val past = LocalDate(2026, 6, 10)
        // Covered and past, but this device has no definition row — so it cannot tell an abandoned
        // series from a live one, and says so instead of guessing.
        val world = firingWorld(past, definitionState = null)

        assertEquals(OccurrenceState.Unknown, world.readingOn(past))
    }

    @Test
    fun aFutureCoveredFiringWithNoFactReadsScheduled() = runTest {
        val future = LocalDate(2026, 6, 20)
        val world = firingWorld(future, definitionState = DefinitionState.Active)

        assertEquals(OccurrenceState.Scheduled, world.readingOn(future))
    }

    @Test
    fun todayItselfIsNotYetMissed() = runTest {
        val world = firingWorld(TODAY, definitionState = DefinitionState.Active)

        // The bound is `date < today`, so the day in progress is still Scheduled.
        assertEquals(OccurrenceState.Scheduled, world.readingOn(TODAY))
    }

    @Test
    fun aDatedTaskRowGetsNoOccurrenceReadingAndKeepsItsWorkingState() = runTest {
        val repo = FakeCalendarRepository()
        val task = calendarItem("t1", TODAY, seriesId = null, kind = null, title = "Pay the rent", status = WorkingState.Done)
        repo.setAgenda(TODAY, listOf(task))
        val component = calendarComponent(repo)

        val row = component.awaitFiring("t1")
        // Not a firing, so there is nothing to read — and its own WorkingState stays the genuine
        // fact it always was for a one-off dated item.
        assertNull(row.occurrence)
        assertEquals(WorkingState.Done, row.item.status)
    }

    @Test
    fun theReadingIsRecomputedNeverStored_aNewFactReEmitsWithNoCalendarWrite() = runTest {
        val repo = FakeCalendarRepository()
        val facts = FakeOccurrenceFactStore()
        val coverage = FakeOccurrenceCoverageStore()
        val definitions = FakeDefinitionStateSource()
        repo.setAgenda(TODAY, listOf(calendarItem("ce-1", TODAY)))
        coverage.record(OccurrenceCoverage(ItemKind.Habit, "task-ce-1", TODAY, TODAY))
        definitions.set(ItemKind.Habit, "task-ce-1", DefinitionState.Active)
        val component = calendarComponent(repo, facts = facts, coverage = coverage, definitions = definitions)

        component.state.test {
            var s = awaitItem()
            while (s.agenda.isEmpty()) s = awaitItem()
            assertEquals(OccurrenceState.Scheduled, s.agenda.single().occurrence)
            val rowBefore = s.agenda.single().item
            val refreshesBefore = repo.refreshArgs.size

            // Move only the FACT store.
            facts.record(fact(TODAY, OccurrenceResolution.DoneOnTime, doneAt = "2026-06-15T08:00:00Z"))
            while (s.agenda.singleOrNull()?.occurrence != OccurrenceState.DoneOnTime) s = awaitItem()

            // The feed row is byte-identical and nothing re-pulled the window: the chip changed
            // because the reading was recomputed, not because anything was written back into the
            // calendar cache — which is exactly what makes it correct on a day that has since passed.
            assertEquals(rowBefore, s.agenda.single().item)
            assertEquals(refreshesBefore, repo.refreshArgs.size)
            assertEquals(0, repo.reconcileCount)
            cancelAndIgnoreRemainingEvents()
        }
    }

    // ---- fixtures ------------------------------------------------------------------------------

    /** The stored fact for the single Habit firing the reading tests use (`task-ce-1`). */
    private fun fact(
        date: LocalDate,
        resolution: OccurrenceResolution,
        doneAt: String? = null,
        completeBy: String? = null,
    ) = OccurrenceFact(
        kind = ItemKind.Habit,
        definitionId = "task-ce-1",
        date = date,
        resolution = resolution,
        doneAt = doneAt?.let { Instant.parse(it) },
        completeBy = completeBy?.let { Instant.parse(it) },
    )

    /**
     * One Habit firing on [date], with every reading input dialled in. Defaults to *covered* and
     * *Active* — the arrangement that derives Missed — so each test above changes exactly one input
     * and the arm it exercises is unambiguous.
     */
    private suspend fun TestScope.firingWorld(
        date: LocalDate,
        coverage: OccurrenceCoverage = OccurrenceCoverage(ItemKind.Habit, "task-ce-1", date, date),
        definitionState: DefinitionState? = DefinitionState.Active,
    ): FiringWorld {
        val repo = FakeCalendarRepository()
        repo.setAgenda(date, listOf(calendarItem("ce-1", date)))
        val facts = FakeOccurrenceFactStore()
        val coverageStore = FakeOccurrenceCoverageStore()
        val definitions = FakeDefinitionStateSource()
        // The fakes hold plain state, so the whole world is arranged before the component ever
        // collects — no scheduler dance is needed to make the arrangement land.
        coverageStore.record(coverage)
        definitionState?.let { definitions.set(ItemKind.Habit, "task-ce-1", it) }
        return FiringWorld(
            facts,
            calendarComponent(repo, facts = facts, coverage = coverageStore, definitions = definitions),
        )
    }

    private class FiringWorld(val facts: FakeOccurrenceFactStore, val component: CalendarComponent)

    /** Select [date] and read the single firing's derived state. */
    private suspend fun FiringWorld.readingOn(date: LocalDate): OccurrenceState? {
        component.onDaySelected(date)
        return component.awaitFiring("ce-1").occurrence
    }

    /** Await the state that actually contains row [id] (past the `stateIn` seed and the empty agenda). */
    private suspend fun CalendarComponent.awaitFiring(id: String): CalendarFiring {
        var found: CalendarFiring? = null
        state.test {
            var s = awaitItem()
            while (s.agenda.none { it.item.id == id }) s = awaitItem()
            found = s.agenda.first { it.item.id == id }
            cancelAndIgnoreRemainingEvents()
        }
        return found!!
    }
}

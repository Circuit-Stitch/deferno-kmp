package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.definition.DefinitionExtras
import com.circuitstitch.deferno.core.data.definition.DefinitionRepository
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.DayFiring
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurringDefinition
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.mergeCoverage
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The read-only recurring detail (#383), and specifically its **today** reading — the half ADR-0053
 * decision 4 turns on.
 *
 * Every assertion here is about honesty rather than completeness: the three ways of not knowing
 * (an unreproducible grid, an unsynced day, an uncached definition) must read as three different
 * things, because collapsing any pair of them is how a client ends up confidently telling someone
 * they missed a habit they never had.
 *
 * `today` is pinned to a fixed date — the repo anchor — and never read from a clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefinitionDetailComponentTest {

    // Monday. The weekly rule below fires on Wednesday, so "today" deliberately misses it.
    private val monday = LocalDate(2026, 6, 15)
    private val wednesday = LocalDate(2026, 6, 17)
    private val ref = ItemRef("h", ItemKind.Habit)

    // The wire's weekday tokens are "Mon".."Sun" (core/model Recurrence.kt `WireWeekdayTokens`), NOT
    // RFC 5545's two-letter "WE" — an unrecognised token refuses the whole grid as UnplaceableWeekday.
    private fun weeklyOnWednesday() = Recurrence(Cadence.Weekly(listOf("Wed")))

    private fun inputs() = SeriesInputs(
        anchorLocal = LocalDateTime(2026, 6, 3, 9, 0),
        tzid = "UTC",
    )

    private fun definition(
        recurrence: Recurrence? = weeklyOnWednesday(),
        series: SeriesInputs? = inputs(),
        state: DefinitionState = DefinitionState.Active,
    ) = RecurringDefinition(
        id = "h",
        kind = ItemKind.Habit,
        title = "Take a Walk",
        definitionState = state,
        description = "a gentle loop",
        labels = listOf("health"),
        recurrence = recurrence,
        series = series,
    )

    private fun TestScope.component(
        definition: RecurringDefinition? = definition(),
        facts: FakeFactStore = FakeFactStore(),
        coverage: FakeCoverageStore = FakeCoverageStore(),
        today: LocalDate = monday,
        extras: DefinitionExtras? = null,
    ) = DefaultDefinitionDetailComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        ref = ref,
        definitionRepository = object : DefinitionRepository {
            override fun observe(ref: ItemRef): Flow<RecurringDefinition?> = flowOf(definition)
            override suspend fun hydrate(ref: ItemRef): DefinitionExtras? = extras
        },
        occurrenceFacts = facts,
        occurrenceCoverage = coverage,
        today = { today },
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    private fun TestScope.collect(c: DefaultDefinitionDetailComponent) {
        backgroundScope.launch { c.state.collect {} }
        advanceUntilIdle()
    }

    @Test
    fun projectsTheCachedDefinitionAndBridgesItToTheSharedItemReading() = runTest {
        val c = component()
        collect(c)

        val s = c.state.value
        assertEquals("Take a Walk", s.definition?.title)
        assertEquals(listOf("health"), s.definition?.labels)
        assertEquals("a gentle loop", s.definition?.description)
        // The Item bridge is what lets the detail reuse recurrenceReading/recurrenceCursor rather than
        // growing a fifth copy of cadence normalisation (#384's whole point).
        assertEquals(weeklyOnWednesday(), s.item?.recurrence)
        assertEquals(ItemKind.Habit, s.item?.kind)
    }

    /**
     * An unsynced day. The grid can say the rule fires; nothing local can say how it went — and
     * without coverage, saying "Missed" would be inventing evidence out of ignorance.
     */
    @Test
    fun readsUnknownWhenTheDayIsOutsideCoverage() = runTest {
        val c = component(today = wednesday)
        collect(c)

        assertEquals(OccurrenceState.Unknown, c.state.value.today.state)
        assertIs<DayFiring.Fires>(c.state.value.today.firing, "the grid still answers which days")
        assertTrue(c.state.value.today.isDue)
    }

    /** Inside coverage, an absent resolution IS evidence — the firing is simply unresolved. */
    @Test
    fun readsScheduledInsideCoverageWithNoStoredResolution() = runTest {
        val coverage = FakeCoverageStore().apply { seed(OccurrenceCoverage(ItemKind.Habit, "h", wednesday, wednesday)) }
        val c = component(coverage = coverage, today = wednesday)
        collect(c)

        assertEquals(OccurrenceState.Scheduled, c.state.value.today.state)
    }

    @Test
    fun readsTheStoredResolutionWhenOneExists() = runTest {
        val facts = FakeFactStore().apply {
            seed(OccurrenceFact(ItemKind.Habit, "h", wednesday, OccurrenceResolution.DoneOnTime))
        }
        val c = component(facts = facts, today = wednesday)
        collect(c)

        assertEquals(OccurrenceState.DoneOnTime, c.state.value.today.state)
    }

    /**
     * The elision. A `null` series block means *this device cannot reproduce that grid* — never *that
     * grid has no firings*. The View must not render "not scheduled today" off this.
     */
    @Test
    fun readsTheGridAsUnavailableWhenTheSeriesBlockWasElided() = runTest {
        val c = component(definition = definition(series = null), today = wednesday)
        collect(c)

        assertEquals(DayFiring.Unavailable, c.state.value.today.firing)
        assertTrue(!c.state.value.today.isDue, "an unreproducible grid is never 'due'")
    }

    /**
     * The pairing every TODAY cell got wrong: an unexpandable grid on a day the server DID answer for.
     *
     * Every successful hydrate records coverage (the server always answers for the recurring kinds), and
     * `resolveOccurrenceState` derives `Scheduled` from coverage + "today has not passed" with no stored
     * record behind it. The three renderers all tested `state == Unknown` to decide whether to say "not
     * available", so this pairing slipped past all of them and rendered the confident "Scheduled" chip
     * for a grid nobody could expand — the mirror of the "not scheduled today" lie, and the reason the
     * reading now carries `isStoredResolution` rather than leaving each surface to guess.
     */
    @Test
    fun aCoveredDayWithNoRecordAndNoGridIsNotAKnownState() = runTest {
        val coverage = FakeCoverageStore().apply { seed(OccurrenceCoverage(ItemKind.Habit, "h", wednesday, wednesday)) }
        val c = component(definition = definition(series = null), coverage = coverage, today = wednesday)
        collect(c)

        val today = c.state.value.today
        assertEquals(DayFiring.Unavailable, today.firing)
        assertEquals(OccurrenceState.Scheduled, today.state, "derived, purely from coverage + the date")
        assertTrue(!today.isStoredResolution, "but nothing was actually recorded")
        assertTrue(!today.isStateKnown, "so the honest line is 'not available', not the Scheduled chip")
    }

    /** …and the same grid WITH a stored resolution does know the day, so the fact is shown. */
    @Test
    fun aStoredResolutionIsKnownEvenWithoutAGrid() = runTest {
        val facts = FakeFactStore().apply {
            seed(OccurrenceFact(ItemKind.Habit, "h", wednesday, OccurrenceResolution.DoneOnTime))
        }
        val coverage = FakeCoverageStore().apply { seed(OccurrenceCoverage(ItemKind.Habit, "h", wednesday, wednesday)) }
        val c = component(definition = definition(series = null), facts = facts, coverage = coverage, today = wednesday)
        collect(c)

        val today = c.state.value.today
        assertEquals(DayFiring.Unavailable, today.firing)
        assertTrue(today.isStoredResolution)
        assertTrue(today.isStateKnown)
        assertEquals(OccurrenceState.DoneOnTime, today.state)
    }

    /** The grid reproduces fine and simply puts nothing on today — a different statement entirely. */
    @Test
    fun readsNotFiringOnADayTheRuleMisses() = runTest {
        val c = component(today = monday)
        collect(c)

        assertEquals(DayFiring.NotFiring, c.state.value.today.firing)
    }

    @Test
    fun anUncachedDefinitionReportsMissingOnlyAfterHydrationSettles() = runTest {
        val c = component(definition = null)
        collect(c)

        assertNull(c.state.value.definition)
        assertTrue(!c.state.value.isHydrating, "hydration has settled")
        assertTrue(c.state.value.isMissing, "so 'not found' is now the honest reading")
    }

    /** The chain rides the hydrate and lands in state; #395 renders it, this issue only carries it. */
    @Test
    fun carriesTheChainAndOriginLabelFromTheHydrate() = runTest {
        val chain = com.circuitstitch.deferno.core.model.SeriesChain(head = "h", requested = "h", truncated = true)
        val c = component(extras = DefinitionExtras(chain = chain, originLabel = "acme/repo#4"))
        collect(c)

        assertEquals(chain, c.state.value.eras)
        assertEquals("acme/repo#4", c.state.value.originLabel)
    }

    @Test
    fun closeEmitsTheClosedOutput() = runTest {
        val outputs = mutableListOf<DefinitionDetailComponent.Output>()
        val c = DefaultDefinitionDetailComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            ref = ref,
            definitionRepository = DefinitionRepository.NONE,
            occurrenceFacts = FakeFactStore(),
            occurrenceCoverage = FakeCoverageStore(),
            today = { monday },
            output = outputs::add,
            coroutineContext = StandardTestDispatcher(testScheduler),
        )

        c.onCloseClicked()

        assertEquals(listOf<DefinitionDetailComponent.Output>(DefinitionDetailComponent.Output.Closed), outputs)
    }
}

private class FakeFactStore : OccurrenceFactLocalStore {
    private val rows = MutableStateFlow<List<OccurrenceFact>>(emptyList())

    fun seed(fact: OccurrenceFact) { rows.value = rows.value + fact }

    override fun observeOn(date: LocalDate) = rows.map { all -> all.filter { it.date == date } }
    override fun observeInRange(kind: ItemKind, definitionId: String, from: LocalDate, to: LocalDate) =
        rows.map { all -> all.filter { it.kind == kind && it.definitionId == definitionId && it.date in from..to } }

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate) =
        rows.map { all -> all.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date } }

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate) =
        rows.value.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date }

    override suspend fun upsert(fact: OccurrenceFact) { rows.value = rows.value + fact }
    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {}
    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) {}

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) {
        block(this)
    }
}

private class FakeCoverageStore : OccurrenceCoverageLocalStore {
    private val ranges = MutableStateFlow<List<OccurrenceCoverage>>(emptyList())

    fun seed(coverage: OccurrenceCoverage) { ranges.value = ranges.value.mergeCoverage(coverage) }

    override fun observeCovering(date: LocalDate) = ranges.map { all -> all.filter { it.covers(date) } }
    override suspend fun get(kind: ItemKind, definitionId: String) =
        ranges.value.filter { it.kind == kind && it.definitionId == definitionId }

    override suspend fun record(coverage: OccurrenceCoverage) { ranges.value = ranges.value.mergeCoverage(coverage) }
    override suspend fun clear(kind: ItemKind, definitionId: String) {}
}

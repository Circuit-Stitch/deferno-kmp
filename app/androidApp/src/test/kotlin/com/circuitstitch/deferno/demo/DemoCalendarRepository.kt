package com.circuitstitch.deferno.demo

import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.definition.DefinitionRef
import com.circuitstitch.deferno.core.data.definition.DefinitionStateSource
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceCoverageLocalStore
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceCoverage
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * In-memory [CalendarRepository] **test fake** for the Calendar Destination's screenshot/View tests
 * (#74). Reads are local Flows over fixed sample data — no network or database; `refreshWindow` /
 * `reconcile` are no-ops (the sample is the source of truth). The real app reads the DI-provided
 * OfflineCalendarRepository (ADR-0014); this stays a test fixture.
 *
 * The sample rows are served verbatim, which is what production does too (#380): a row's `kind` is a
 * stored column, so what the feed asserted is what the agenda reads back — a sample row with no series
 * degrades to read-only exactly as it would in the app.
 */
internal class DemoCalendarRepository(
    private val markers: Map<LocalDate, Int> = emptyMap(),
    private val agenda: Map<LocalDate, List<CalendarItem>> = emptyMap(),
) : CalendarRepository {

    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
        MutableStateFlow(markers.filterKeys { it >= from && it < to })

    override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> =
        MutableStateFlow(agenda[date] ?: emptyList())

    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
    override suspend fun reconcile() {}
}

/**
 * Read-only [OccurrenceFactLocalStore] over a fixed fact list — the *stored resolutions* half of the
 * screenshot harness's occurrence-state reading. Writes are no-ops: a static capture never writes.
 */
internal class DemoOccurrenceFactStore(
    private val facts: List<OccurrenceFact> = emptyList(),
) : OccurrenceFactLocalStore {

    override fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>> =
        MutableStateFlow(facts.filter { it.date == date })

    override fun observeInRange(kind: ItemKind, definitionId: String, from: LocalDate, to: LocalDate) =
        MutableStateFlow(
            facts.filter { it.kind == kind && it.definitionId == definitionId && it.date >= from && it.date <= to },
        )

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate) =
        MutableStateFlow(facts.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date })

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate) =
        facts.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date }

    override suspend fun upsert(fact: OccurrenceFact) {}
    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {}
    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) {}

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) = block(this)
}

/** Read-only [OccurrenceCoverageLocalStore] over fixed ranges — the "have we synced this?" half. */
internal class DemoOccurrenceCoverageStore(
    private val ranges: List<OccurrenceCoverage> = emptyList(),
) : OccurrenceCoverageLocalStore {

    override fun observeCovering(date: LocalDate): Flow<List<OccurrenceCoverage>> =
        MutableStateFlow(ranges.filter { it.covers(date) })

    override suspend fun get(kind: ItemKind, definitionId: String) =
        ranges.filter { it.kind == kind && it.definitionId == definitionId }

    override suspend fun record(coverage: OccurrenceCoverage) {}
    override suspend fun clear(kind: ItemKind, definitionId: String) {}
}

/** Read-only [DefinitionStateSource] over a fixed map — each sample definition's light switch. */
internal class DemoDefinitionStateSource(
    private val states: Map<DefinitionRef, DefinitionState> = emptyMap(),
) : DefinitionStateSource {
    override fun observeAll(): Flow<Map<DefinitionRef, DefinitionState>> = MutableStateFlow(states)
    override suspend fun get(kind: ItemKind, definitionId: String) = states[DefinitionRef(kind, definitionId)]
}

/**
 * Sample Calendar content for the screenshot tests (#74): a small, calm month (design-principles.md) —
 * a few days with marker dots, and a selected day whose agenda shows a Habit / Chore / Event firing
 * plus a dated Task, so the kind-aware action set, the read-only degradation and the occurrence-state
 * chips all render in the baseline.
 *
 * Every firing keeps its item id (`task-…`) distinct from its series id (`…-series`) — the distinction
 * #380 turns on: the occurrence endpoints address the item, while the series id only says "this row is
 * a firing".
 *
 * **A firing's chip comes from [facts] + [coverage] + [definitionStates] against [day], never from the
 * row's `WorkingState`** (ADR-0053 decision 4). The sample is arranged so the baseline shows a real
 * spread: the Habit is on record as done on time, the Chore was set aside, and the Event is covered
 * with nothing on record on a day that is *today*, so it reads Scheduled rather than Missed.
 */
internal object SampleCalendar {
    val day: LocalDate = LocalDate(2026, 6, 15)

    private fun item(
        id: String,
        kind: ItemKind,
        seriesId: String?,
        title: String,
        status: WorkingState = WorkingState.Open,
    ) = CalendarItem(
        id = id,
        taskId = "task-$id",
        seriesId = seriesId,
        title = title,
        date = day,
        start = Instant.parse("2026-06-15T09:00:00Z"),
        end = Instant.parse("2026-06-15T09:30:00Z"),
        allDay = false,
        status = status,
        kind = kind,
        source = CalendarSource.Deferno,
    )

    val agenda: Map<LocalDate, List<CalendarItem>> = mapOf(
        day to listOf(
            item("h1", ItemKind.Habit, "hab-1-series", "Morning stretch"),
            item("c1", ItemKind.Chore, "cho-1-series", "Water the plants"),
            item("e1", ItemKind.Event, "evt-1-series", "Team standup"),
            // A one-off dated Task (no series) — rendered, read-only (acted on in Tasks). Its
            // WorkingState is the one on this screen that still means something: a Task's own progress.
            item("t1", ItemKind.Task, seriesId = null, title = "Pay the rent", status = WorkingState.Done),
        ),
    )

    /** What the server has on record for [day]'s firings. The Event has none — it is simply not due yet. */
    val facts: List<OccurrenceFact> = listOf(
        OccurrenceFact(
            kind = ItemKind.Habit,
            definitionId = "task-h1",
            date = day,
            resolution = OccurrenceResolution.DoneOnTime,
            doneAt = Instant.parse("2026-06-15T07:40:00Z"),
            completeBy = Instant.parse("2026-06-15T09:00:00Z"),
        ),
        OccurrenceFact(
            kind = ItemKind.Chore,
            definitionId = "task-c1",
            date = day,
            resolution = OccurrenceResolution.Skipped,
        ),
    )

    /** [day] is synced for all three recurring definitions, so an absent fact is evidence, not ignorance. */
    val coverage: List<OccurrenceCoverage> = listOf(
        OccurrenceCoverage(ItemKind.Habit, "task-h1", day, day),
        OccurrenceCoverage(ItemKind.Chore, "task-c1", day, day),
        OccurrenceCoverage(ItemKind.Event, "task-e1", day, day),
    )

    val definitionStates: Map<DefinitionRef, DefinitionState> = mapOf(
        DefinitionRef(ItemKind.Habit, "task-h1") to DefinitionState.Active,
        DefinitionRef(ItemKind.Chore, "task-c1") to DefinitionState.Active,
        DefinitionRef(ItemKind.Event, "task-e1") to DefinitionState.Active,
    )

    val markers: Map<LocalDate, Int> = mapOf(
        LocalDate(2026, 6, 3) to 1,
        LocalDate(2026, 6, 8) to 2,
        day to 4,
        LocalDate(2026, 6, 22) to 1,
    )
}

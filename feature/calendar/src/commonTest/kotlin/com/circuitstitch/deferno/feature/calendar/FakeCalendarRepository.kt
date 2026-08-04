package com.circuitstitch.deferno.feature.calendar

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
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.mergeCoverage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

/**
 * In-memory [CalendarRepository] for component tests: a [MutableStateFlow] of the grid markers and a
 * map of per-day agendas the tests mutate, plus recorded `refreshWindow`/`reconcile` calls.
 */
class FakeCalendarRepository : CalendarRepository {
    val markers = MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
    private val agendas = MutableStateFlow<Map<LocalDate, List<CalendarItem>>>(emptyMap())

    val refreshArgs = mutableListOf<Triple<LocalDate, LocalDate, String>>()
    var reconcileCount = 0
        private set

    fun setAgenda(date: LocalDate, items: List<CalendarItem>) {
        agendas.value = agendas.value + (date to items)
    }

    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> = markers
    override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> = agendas.map { it[date] ?: emptyList() }

    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {
        refreshArgs += Triple(from, to, tz)
    }

    override suspend fun reconcile() {
        reconcileCount++
    }
}

/**
 * In-memory [OccurrenceFactLocalStore] for the component tests — the *facts* half of the reading.
 * Only the read surface the agenda uses is live; the write surface exists so the tests can move the
 * store under a collector and watch the state re-derive, which is the property that proves the
 * reading is computed rather than cached.
 */
class FakeOccurrenceFactStore : OccurrenceFactLocalStore {
    private val facts = MutableStateFlow<List<OccurrenceFact>>(emptyList())

    /** Put a fact on record (replacing any fact for the same firing), as a sync or an optimistic write would. */
    fun record(fact: OccurrenceFact) {
        facts.value = facts.value.filterNot {
            it.kind == fact.kind && it.definitionId == fact.definitionId && it.date == fact.date
        } + fact
    }

    override fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>> =
        facts.map { all -> all.filter { it.date == date } }

    override fun observeInRange(kind: ItemKind, definitionId: String, from: LocalDate, to: LocalDate) =
        facts.map { all ->
            all.filter { it.kind == kind && it.definitionId == definitionId && it.date >= from && it.date <= to }
        }

    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate) =
        facts.map { all -> all.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date } }

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate) =
        facts.value.firstOrNull { it.kind == kind && it.definitionId == definitionId && it.date == date }

    override suspend fun upsert(fact: OccurrenceFact) = record(fact)

    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {
        facts.value = facts.value.filterNot { it.kind == kind && it.definitionId == definitionId && it.date == date }
    }

    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) {
        this.facts.value = this.facts.value.filterNot {
            it.kind == kind && it.definitionId == definitionId && it.date >= from && it.date <= to
        } + facts
    }

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) = block(this)
}

/** In-memory [OccurrenceCoverageLocalStore] for the component tests — the "have we actually looked?" half. */
class FakeOccurrenceCoverageStore : OccurrenceCoverageLocalStore {
    private val ranges = MutableStateFlow<List<OccurrenceCoverage>>(emptyList())

    override fun observeCovering(date: LocalDate): Flow<List<OccurrenceCoverage>> =
        ranges.map { all -> all.filter { it.covers(date) } }

    override suspend fun get(kind: ItemKind, definitionId: String) =
        ranges.value.filter { it.kind == kind && it.definitionId == definitionId }

    // Through the real `mergeCoverage`, so the fake cannot accidentally swallow a gap the production
    // store would keep — the one behaviour whose loss would silently turn Unknown into Missed.
    override suspend fun record(coverage: OccurrenceCoverage) {
        ranges.value = ranges.value.mergeCoverage(coverage)
    }

    override suspend fun clear(kind: ItemKind, definitionId: String) {
        ranges.value = ranges.value.filterNot { it.kind == kind && it.definitionId == definitionId }
    }
}

/** In-memory [DefinitionStateSource] for the component tests — each definition's Active/Archived light switch. */
class FakeDefinitionStateSource(
    initial: Map<DefinitionRef, DefinitionState> = emptyMap(),
) : DefinitionStateSource {
    private val states = MutableStateFlow(initial)

    fun set(kind: ItemKind, definitionId: String, state: DefinitionState) {
        states.value = states.value + (DefinitionRef(kind, definitionId) to state)
    }

    override fun observeAll(): Flow<Map<DefinitionRef, DefinitionState>> = states
    override suspend fun get(kind: ItemKind, definitionId: String) = states.value[DefinitionRef(kind, definitionId)]
}

/** Recording [OccurrenceEditor] for the component tests. */
class RecordingOccurrenceEditor : OccurrenceEditor {
    val calls = mutableListOf<String>()
    override suspend fun mark(itemId: String, action: com.circuitstitch.deferno.core.model.OccurrenceAction) {
        calls += "mark:$itemId:$action"
    }
    override suspend fun clear(itemId: String) { calls += "clear:$itemId" }
    override suspend fun reschedule(itemId: String, newDate: LocalDate) { calls += "reschedule:$itemId:$newDate" }
}

/**
 * A feed row for the component tests. [status] survives, but it is **not** the source of a firing's
 * chip any more: a firing's reading comes from the fact/coverage/definition-state join, and this field
 * is meaningful only for the rows that are not firings (a one-off dated Task — `seriesId = null`).
 * Setting it on a Habit row asserts nothing the agenda reads.
 */
internal fun calendarItem(
    id: String,
    date: LocalDate,
    seriesId: String? = "hab-1",
    kind: ItemKind? = ItemKind.Habit,
    title: String = "Morning stretch",
    status: WorkingState = WorkingState.Open,
) = CalendarItem(
    id = id,
    taskId = "task-$id",
    seriesId = seriesId,
    title = title,
    date = date,
    start = Instant.parse("${date}T09:00:00Z"),
    end = Instant.parse("${date}T09:15:00Z"),
    allDay = false,
    status = status,
    kind = kind,
    source = CalendarSource.Deferno,
)

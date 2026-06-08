package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The offline-first occurrence write path (#74, ADR-0001) — the firing-level sibling of
 * `OutboxTaskWriterTest`. It proves the optimistic apply (the cached firing's [WorkingState] / date
 * updates instantly) + the outbox enqueue (the kind-scoped request is queued for replay), and the
 * **no-op guard**: a one-off dated Task and an unresolved-kind firing are not actionable, so nothing is
 * applied or enqueued (the UI never offers occurrence actions there anyway).
 */
class OutboxOccurrenceWriterTest {

    private val date = LocalDate(2026, 6, 8)
    private val now = Instant.parse("2026-06-08T10:00:00Z")

    private fun firing(id: String = "ce-1", seriesId: String? = "hab-3") = CalendarItem(
        id = id,
        taskId = "task-1",
        seriesId = seriesId,
        title = "Morning stretch",
        date = date,
        start = Instant.parse("2026-06-08T09:00:00Z"),
        end = Instant.parse("2026-06-08T09:15:00Z"),
        allDay = false,
        status = WorkingState.Open,
        kind = null,
        source = CalendarSource.Deferno,
    )

    @Test
    fun markAppliesOptimisticallyAndEnqueuesTheKindScopedRequest() = runTest {
        val store = InMemoryCalendarStore().apply {
            replaceSeriesKinds(mapOf("hab-3" to ItemKind.Habit))
            seed(firing())
        }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        writer.mark("ce-1", OccurrenceAction.Complete)

        // Optimistic: the cached firing reads Done instantly.
        assertEquals(WorkingState.Done, store.get("ce-1")?.status)
        // Enqueued: the habit-binary done request rides the outbox for replay.
        val entry = outbox.all.single()
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("habits", "hab-3", "occurrences"), entry.request.path)
        assertEquals("""{"done":true,"date":"2026-06-08"}""", entry.request.body)
        assertEquals("occurrence:Habit:hab-3:2026-06-08", entry.target)
    }

    @Test
    fun rescheduleMovesTheRowAndClearResetsIt() = runTest {
        val store = InMemoryCalendarStore().apply {
            replaceSeriesKinds(mapOf("evt-1" to ItemKind.Event))
            seed(firing(seriesId = "evt-1").copy(status = WorkingState.Done))
        }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        writer.reschedule("ce-1", LocalDate(2026, 6, 10))
        assertEquals(LocalDate(2026, 6, 10), store.get("ce-1")?.date)

        writer.clear("ce-1")
        assertEquals(WorkingState.Open, store.get("ce-1")?.status)

        assertEquals(2, outbox.all.size)
    }

    @Test
    fun aHabitMarkOnlyHonorsComplete_neverSilentlyUncompletes() = runTest {
        val store = InMemoryCalendarStore().apply {
            replaceSeriesKinds(mapOf("hab-3" to ItemKind.Habit))
            seed(firing(seriesId = "hab-3").copy(status = WorkingState.Done))
        }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        // Start / Skip are meaningless for a binary habit — they would build {done:false}, un-completing
        // it. The writer ignores them (Clear is the explicit un-complete path); nothing is applied or queued.
        writer.mark("ce-1", OccurrenceAction.Start)
        writer.mark("ce-1", OccurrenceAction.Skip)
        assertEquals(WorkingState.Done, store.get("ce-1")?.status)
        assertTrue(outbox.all.isEmpty())

        // Complete is honored.
        writer.mark("ce-1", OccurrenceAction.Complete)
        assertEquals(1, outbox.all.size)
    }

    @Test
    fun aOneOffTaskAndAnUnresolvedKindFiringAreNoOps() = runTest {
        val store = InMemoryCalendarStore().apply {
            replaceSeriesKinds(mapOf("hab-3" to ItemKind.Habit))
            seed(firing(id = "one-off", seriesId = null)) // a dated Task — not an occurrence
            seed(firing(id = "web-only", seriesId = "unindexed")) // recurring but kind unresolved
        }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        writer.mark("one-off", OccurrenceAction.Complete)
        writer.mark("web-only", OccurrenceAction.Complete)

        // Neither is actionable: status untouched, nothing enqueued.
        assertEquals(WorkingState.Open, store.get("one-off")?.status)
        assertEquals(WorkingState.Open, store.get("web-only")?.status)
        assertTrue(outbox.all.isEmpty())
    }
}

/** A minimal in-memory [CalendarLocalStore] for the writer test: get resolves kind from the index, like the real store. */
private class InMemoryCalendarStore : CalendarLocalStore {
    private val rows = mutableMapOf<String, CalendarItem>()
    private var index = mapOf<String, ItemKind>()

    fun seed(item: CalendarItem) { rows[item.id] = item }

    override suspend fun get(id: String): CalendarItem? = rows[id]?.let { it.copy(kind = index[it.seriesId]) }
    override suspend fun upsert(item: CalendarItem) { rows[item.id] = item }
    override suspend fun replaceSeriesKinds(index: Map<String, ItemKind>) { this.index = index }

    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>> = flowOf(emptyList())
    override fun observeByDate(date: LocalDate): Flow<List<CalendarItem>> = flowOf(emptyList())
    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> = flowOf(emptyMap())
    override suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>) {}
}

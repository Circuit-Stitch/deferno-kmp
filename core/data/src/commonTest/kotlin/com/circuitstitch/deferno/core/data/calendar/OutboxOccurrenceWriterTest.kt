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
 *
 * The fixture keeps `taskId` and `seriesId` **distinct** on purpose (#380): `seriesId` is what makes the
 * row a firing, but the *item* id is what goes in the endpoint's path slot. Conflating the two is
 * exactly the bug this file now pins against.
 */
class OutboxOccurrenceWriterTest {

    private val date = LocalDate(2026, 6, 8)
    private val now = Instant.parse("2026-06-08T10:00:00Z")

    private fun firing(
        id: String = "ce-1",
        seriesId: String? = "hab-3-series",
        taskId: String = "hab-3-item",
        kind: ItemKind? = ItemKind.Habit,
    ) = CalendarItem(
        id = id,
        taskId = taskId,
        seriesId = seriesId,
        title = "Morning stretch",
        date = date,
        start = Instant.parse("2026-06-08T09:00:00Z"),
        end = Instant.parse("2026-06-08T09:15:00Z"),
        allDay = false,
        status = WorkingState.Open,
        kind = kind,
        source = CalendarSource.Deferno,
    )

    @Test
    fun markAppliesOptimisticallyAndEnqueuesTheKindScopedRequest() = runTest {
        val store = InMemoryCalendarStore().apply { seed(firing()) }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        writer.mark("ce-1", OccurrenceAction.Complete)

        // Optimistic: the cached firing reads Done instantly.
        assertEquals(WorkingState.Done, store.get("ce-1")?.status)
        // Enqueued: the habit-binary done request rides the outbox for replay, addressed by the ITEM id
        // (#380) — the series id in that slot 404s, and the sender maps 404 to success, so the write
        // would evaporate with no error at all.
        val entry = outbox.all.single()
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("habits", "hab-3-item", "occurrences"), entry.request.path)
        assertEquals("""{"done":true,"date":"2026-06-08"}""", entry.request.body)
        assertEquals("occurrence:Habit:hab-3-item:2026-06-08", entry.target)
    }

    @Test
    fun everyVerbAddressesTheItemIdNotTheSeriesId() = runTest {
        // #380 across all three verbs and all three kinds: the kind picks the endpoint family, but no
        // request or target may ever carry the series id.
        for ((kind, path) in listOf(ItemKind.Chore to "chores", ItemKind.Event to "events")) {
            val store = InMemoryCalendarStore().apply {
                seed(firing(seriesId = "s-$path", taskId = "i-$path", kind = kind))
            }
            val outbox = FakeOutboxStore()
            val writer = OutboxOccurrenceWriter(store, outbox) { now }

            writer.mark("ce-1", OccurrenceAction.Complete)
            writer.clear("ce-1")
            writer.reschedule("ce-1", LocalDate(2026, 6, 10))

            assertEquals(3, outbox.all.size)
            for (entry in outbox.all) {
                assertEquals("i-$path", entry.request.path[1], "$kind ${entry.request.path} must address the item id")
                assertEquals("occurrence:${kind.name}:i-$path:2026-06-08", entry.target)
            }
        }
    }

    @Test
    fun rescheduleMovesTheRowAndClearResetsIt() = runTest {
        val store = InMemoryCalendarStore().apply {
            seed(
                firing(seriesId = "evt-1-series", taskId = "evt-1-item", kind = ItemKind.Event)
                    .copy(status = WorkingState.Done),
            )
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
    fun aHabitFiringCanBeRescheduled() = runTest {
        // #380 defect 3: habit + chore reschedule ship server-side over the shared
        // `reschedule_recurring_occurrence`, so the writer must enqueue one rather than no-op.
        val store = InMemoryCalendarStore().apply { seed(firing()) }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        writer.reschedule("ce-1", LocalDate(2026, 6, 10))

        assertEquals(LocalDate(2026, 6, 10), store.get("ce-1")?.date)
        assertEquals(
            listOf("habits", "hab-3-item", "occurrences", "2026-06-08", "reschedule"),
            outbox.all.single().request.path,
        )
    }

    @Test
    fun aHabitMarkOnlyHonorsComplete_neverSilentlyUncompletes() = runTest {
        val store = InMemoryCalendarStore().apply { seed(firing().copy(status = WorkingState.Done)) }
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
            seed(firing(id = "one-off", seriesId = null, kind = ItemKind.Task)) // a dated Task
            seed(firing(id = "web-only", kind = null)) // recurring, but its stored kind token is unknown
        }
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        // Every verb, not just mark: clear and reschedule share the same guard and would otherwise
        // enqueue a request the endpoints cannot route.
        for (id in listOf("one-off", "web-only")) {
            writer.mark(id, OccurrenceAction.Complete)
            writer.clear(id)
            writer.reschedule(id, LocalDate(2026, 6, 10))
        }

        // Neither is actionable: status and date untouched, nothing enqueued.
        assertEquals(WorkingState.Open, store.get("one-off")?.status)
        assertEquals(WorkingState.Open, store.get("web-only")?.status)
        assertEquals(date, store.get("one-off")?.date)
        assertTrue(outbox.all.isEmpty())
    }

    @Test
    fun anUnknownRowIdIsANoOp() = runTest {
        // The View can only pass an id it rendered, but the seam is public — a stale id must not throw.
        val store = InMemoryCalendarStore()
        val outbox = FakeOutboxStore()
        val writer = OutboxOccurrenceWriter(store, outbox) { now }

        writer.mark("gone", OccurrenceAction.Complete)
        writer.clear("gone")
        writer.reschedule("gone", LocalDate(2026, 6, 10))

        assertTrue(outbox.all.isEmpty())
    }
}

/** A minimal in-memory [CalendarLocalStore] for the writer test: a row reads back exactly as written. */
private class InMemoryCalendarStore : CalendarLocalStore {
    private val rows = mutableMapOf<String, CalendarItem>()

    fun seed(item: CalendarItem) { rows[item.id] = item }

    override suspend fun get(id: String): CalendarItem? = rows[id]
    override suspend fun upsert(item: CalendarItem) { rows[item.id] = item }

    override fun observeInRange(from: LocalDate, to: LocalDate): Flow<List<CalendarItem>> = flowOf(emptyList())
    override fun observeByDate(date: LocalDate): Flow<List<CalendarItem>> = flowOf(emptyList())
    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> = flowOf(emptyMap())
    override suspend fun replaceWindow(from: LocalDate, to: LocalDate, items: List<CalendarItem>) {}
}

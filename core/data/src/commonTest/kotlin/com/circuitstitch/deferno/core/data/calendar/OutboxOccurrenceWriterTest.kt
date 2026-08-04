package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.data.outbox.FakeOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OccurrenceFact
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The offline-first occurrence write path (#74, ADR-0001, ADR-0053) — the firing-level sibling of
 * `OutboxTaskWriterTest`. It proves the optimistic apply (the firing's stored resolution updates
 * instantly, on the **fact table** since #390 rather than on the agenda row's [WorkingState]) + the
 * outbox enqueue (the kind-scoped request is queued for replay), and the **no-op guard**: a one-off
 * dated Task and an unresolved-kind firing are not actionable, so nothing is applied or enqueued (the
 * UI never offers occurrence actions there anyway).
 *
 * The fixture keeps `taskId` and `seriesId` **distinct** on purpose (#380): `seriesId` is what makes the
 * row a firing, but the *item* id is what goes in the endpoint's path slot. Conflating the two is
 * exactly the bug this file now pins against. `taskId` is also the fact table's `definitionId`, so the
 * same distinction decides whether a write and a later read address the same firing at all.
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

    private class Scene(rows: List<CalendarItem> = emptyList()) {
        val calendar = InMemoryCalendarStore().apply { rows.forEach { seed(it) } }
        val facts = RecordingFactStore()
        val outbox = FakeOutboxStore()
    }

    private fun scene(vararg rows: CalendarItem) =
        Scene(rows.toList().ifEmpty { listOf(firing()) })

    private fun Scene.writer() = OutboxOccurrenceWriter(calendar, facts, outbox) { now }

    @Test
    fun markAppliesOptimisticallyAndEnqueuesTheKindScopedRequest() = runTest {
        val scene = scene()

        scene.writer().mark("ce-1", OccurrenceAction.Complete)

        // Optimistic: the firing's stored resolution reads done instantly, keyed by the SAME identity
        // the outbox target carries — (kind, definitionId, date), never the local feed-row id.
        val fact = scene.facts.get(ItemKind.Habit, "hab-3-item", date)
        assertEquals(OccurrenceResolution.DoneOnTime, fact?.resolution)
        assertEquals(now, fact?.doneAt)
        // Enqueued: the habit-binary done request rides the outbox for replay, addressed by the ITEM id
        // (#380) — the series id in that slot 404s, and the sender maps 404 to success, so the write
        // would evaporate with no error at all.
        val entry = scene.outbox.all.single()
        assertEquals(OutboxMethod.Post, entry.request.method)
        assertEquals(listOf("habits", "hab-3-item", "occurrences"), entry.request.path)
        assertEquals("""{"done":true,"date":"2026-06-08"}""", entry.request.body)
        assertEquals("occurrence:Habit:hab-3-item:2026-06-08", entry.target)
    }

    @Test
    fun theOptimisticApplyRunsInsideOneTransaction() = runTest {
        // The pre-#390 writer read the cache and wrote it back un-transacted — alone among the three
        // outbox writers. It matters more now: the read decides the write (a completion's punctuality
        // is decided against the deadline the read returned), so a reconcile interleaving between them
        // would be overwritten by a decision taken on data it had already replaced.
        val scene = scene()

        scene.writer().mark("ce-1", OccurrenceAction.Complete)

        assertEquals(listOf("begin", "get", "upsert", "end"), scene.facts.events)
        assertEquals(0, scene.facts.outsideTransaction, "no fact write may happen outside a transaction")
    }

    @Test
    fun aCompletionIsMarkedLateWhenItMissesTheDeadlineAlreadyOnRecord() = runTest {
        // Offline honesty, end to end through the writer: the deadline the last sync recorded is read
        // inside the transaction and decides the punctuality, so the user sees "done late" with no
        // network at all. The old WorkingState apply could only ever say "done".
        val scene = scene()
        scene.facts.seed(
            OccurrenceFact(
                kind = ItemKind.Habit,
                definitionId = "hab-3-item",
                date = date,
                resolution = OccurrenceResolution.Scheduled,
                completeBy = Instant.parse("2026-06-08T09:15:00Z"), // 45 minutes before `now`
            ),
        )

        scene.writer().mark("ce-1", OccurrenceAction.Complete)

        assertEquals(
            OccurrenceResolution.DoneLate,
            scene.facts.get(ItemKind.Habit, "hab-3-item", date)?.resolution,
        )
    }

    @Test
    fun everyVerbAddressesTheItemIdNotTheSeriesId() = runTest {
        // #380 across all three verbs and all three kinds: the kind picks the endpoint family, but no
        // request or target may ever carry the series id.
        for ((kind, path) in listOf(ItemKind.Chore to "chores", ItemKind.Event to "events")) {
            val scene = scene(firing(seriesId = "s-$path", taskId = "i-$path", kind = kind))
            val writer = scene.writer()

            writer.mark("ce-1", OccurrenceAction.Complete)
            writer.clear("ce-1")
            writer.reschedule("ce-1", LocalDate(2026, 6, 10))

            assertEquals(3, scene.outbox.all.size)
            for (entry in scene.outbox.all) {
                assertEquals("i-$path", entry.request.path[1], "$kind ${entry.request.path} must address the item id")
                assertEquals("occurrence:${kind.name}:i-$path:2026-06-08", entry.target)
            }
        }
    }

    @Test
    fun rescheduleMovesTheRowAndRecordsBothDays_andClearForgetsTheFact() = runTest {
        val scene = scene(firing(seriesId = "evt-1-series", taskId = "evt-1-item", kind = ItemKind.Event))
        val writer = scene.writer()

        writer.mark("ce-1", OccurrenceAction.Complete)
        writer.reschedule("ce-1", LocalDate(2026, 6, 10))

        // The agenda row moves days; the vacated day is recorded as the deliberate skip the server
        // writes there, and the destination holds a row that records no progress.
        assertEquals(LocalDate(2026, 6, 10), scene.calendar.get("ce-1")?.date)
        assertEquals(
            OccurrenceResolution.Skipped,
            scene.facts.get(ItemKind.Event, "evt-1-item", date)?.resolution,
        )
        assertEquals(
            OccurrenceResolution.Scheduled,
            scene.facts.get(ItemKind.Event, "evt-1-item", LocalDate(2026, 6, 10))?.resolution,
        )

        // A clear leaves NO record — absence, not a Scheduled fact (ADR-0053 decision 4). It clears the
        // day the agenda row now sits on (the 10th), because the writer resolves the firing from the
        // row it was handed, and the reschedule already moved it there.
        writer.clear("ce-1")
        assertNull(scene.facts.get(ItemKind.Event, "evt-1-item", LocalDate(2026, 6, 10)))

        assertEquals(3, scene.outbox.all.size)
    }

    @Test
    fun aHabitFiringCanBeRescheduled() = runTest {
        // #380 defect 3: habit + chore reschedule ship server-side over the shared
        // `reschedule_recurring_occurrence`, so the writer must enqueue one rather than no-op.
        val scene = scene()

        scene.writer().reschedule("ce-1", LocalDate(2026, 6, 10))

        assertEquals(LocalDate(2026, 6, 10), scene.calendar.get("ce-1")?.date)
        assertEquals(
            listOf("habits", "hab-3-item", "occurrences", "2026-06-08", "reschedule"),
            scene.outbox.all.single().request.path,
        )
    }

    @Test
    fun aHabitMarkOnlyHonorsComplete_neverSilentlyUncompletes() = runTest {
        val scene = scene()
        scene.facts.seed(
            OccurrenceFact(ItemKind.Habit, "hab-3-item", date, OccurrenceResolution.DoneOnTime, doneAt = now),
        )
        val writer = scene.writer()

        // Start / Skip are meaningless for a binary habit — they would build {done:false}, un-completing
        // it. The writer ignores them (Clear is the explicit un-complete path); nothing is applied or queued.
        writer.mark("ce-1", OccurrenceAction.Start)
        writer.mark("ce-1", OccurrenceAction.Skip)
        assertEquals(
            OccurrenceResolution.DoneOnTime,
            scene.facts.get(ItemKind.Habit, "hab-3-item", date)?.resolution,
        )
        assertTrue(scene.outbox.all.isEmpty())

        // Complete is honored.
        writer.mark("ce-1", OccurrenceAction.Complete)
        assertEquals(1, scene.outbox.all.size)
    }

    @Test
    fun aOneOffTaskAndAnUnresolvedKindFiringAreNoOps() = runTest {
        val scene = scene(
            firing(id = "one-off", seriesId = null, kind = ItemKind.Task), // a dated Task
            firing(id = "web-only", kind = null), // recurring, but its stored kind token is unknown
        )
        val writer = scene.writer()

        // Every verb, not just mark: clear and reschedule share the same guard and would otherwise
        // enqueue a request the endpoints cannot route.
        for (id in listOf("one-off", "web-only")) {
            writer.mark(id, OccurrenceAction.Complete)
            writer.clear(id)
            writer.reschedule(id, LocalDate(2026, 6, 10))
        }

        // Neither is actionable: the agenda rows are untouched, no fact was recorded, nothing enqueued.
        assertEquals(date, scene.calendar.get("one-off")?.date)
        assertEquals(date, scene.calendar.get("web-only")?.date)
        assertTrue(scene.facts.rows.isEmpty())
        assertTrue(scene.outbox.all.isEmpty())
    }

    @Test
    fun anUnknownRowIdIsANoOp() = runTest {
        // The View can only pass an id it rendered, but the seam is public — a stale id must not throw.
        val scene = Scene()
        val writer = scene.writer()

        writer.mark("gone", OccurrenceAction.Complete)
        writer.clear("gone")
        writer.reschedule("gone", LocalDate(2026, 6, 10))

        assertTrue(scene.facts.rows.isEmpty())
        assertTrue(scene.outbox.all.isEmpty())
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

/**
 * An in-memory [OccurrenceFactLocalStore] that also **records what the writer did and when**. The
 * [events] log and [outsideTransaction] counter are what let a test prove the read-modify-write is
 * atomic rather than merely correct in isolation — a property no assertion on the resulting rows can
 * distinguish, because an interleaved reconcile leaves the same rows behind on a good day.
 */
private class RecordingFactStore : OccurrenceFactLocalStore {
    val rows = mutableMapOf<Triple<ItemKind, String, LocalDate>, OccurrenceFact>()
    val events = mutableListOf<String>()
    var outsideTransaction = 0
        private set

    private var depth = 0

    fun seed(fact: OccurrenceFact) { rows[key(fact.kind, fact.definitionId, fact.date)] = fact }

    private fun key(kind: ItemKind, definitionId: String, date: LocalDate) = Triple(kind, definitionId, date)

    private fun record(event: String) {
        events += event
        if (depth == 0) outsideTransaction++
    }

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact? {
        record("get")
        return rows[key(kind, definitionId, date)]
    }

    override suspend fun upsert(fact: OccurrenceFact) {
        record("upsert")
        rows[key(fact.kind, fact.definitionId, fact.date)] = fact
    }

    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {
        record("delete")
        rows.remove(key(kind, definitionId, date))
    }

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) {
        events += "begin"
        depth++
        try {
            block(this)
        } finally {
            depth--
            events += "end"
        }
    }

    override fun observeOn(date: LocalDate): Flow<List<OccurrenceFact>> = flowOf(emptyList())
    override fun observeInRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
    ): Flow<List<OccurrenceFact>> = flowOf(emptyList())
    override fun observe(kind: ItemKind, definitionId: String, date: LocalDate): Flow<OccurrenceFact?> = flowOf(null)
    override suspend fun replaceRange(
        kind: ItemKind,
        definitionId: String,
        from: LocalDate,
        to: LocalDate,
        facts: List<OccurrenceFact>,
    ) = Unit
}

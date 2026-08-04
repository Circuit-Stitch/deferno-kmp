package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.data.calendar.CalendarLocalStore
import com.circuitstitch.deferno.core.data.calendar.OutboxOccurrenceWriter
import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
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
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The occurrence offline → online transition end to end (#396) — the firing-level sibling of
 * [OfflineToOnlineTest], and the acceptance criterion for the flush-time coalescer. Wires the real
 * write path (writer → outbox → processor → sender) to the in-memory fakes on the ADR-0006 JVM-fast
 * path: the user fidgets with one firing while offline, and exactly one write reaches the server.
 *
 * The optimistic local state is asserted at every step, because that is the property the collapse must
 * never touch: coalescing is a *queue* optimisation, and the user's cached row is already correct
 * before the first flush ever runs.
 */
class OccurrenceOfflineToOnlineTest {

    private val t0 = Instant.parse("2026-06-08T10:00:00Z")
    private val day = LocalDate(2026, 6, 8)

    private fun firing() = CalendarItem(
        id = "ce-1",
        taskId = "cho-1-item",
        seriesId = "cho-1-series",
        title = "Water the plants",
        date = day,
        start = Instant.parse("2026-06-08T09:00:00Z"),
        end = Instant.parse("2026-06-08T09:15:00Z"),
        allDay = false,
        status = WorkingState.Open,
        kind = ItemKind.Chore,
        source = CalendarSource.Deferno,
    )

    private fun scene(): Scene {
        val calendar = FakeCalendarStore().apply { seed(firing()) }
        val facts = FakeFactStore()
        val outbox = FakeOutboxStore()
        return Scene(calendar, facts, outbox, OutboxOccurrenceWriter(calendar, facts, outbox) { t0 })
    }

    private class Scene(
        val calendar: FakeCalendarStore,
        val facts: FakeFactStore,
        val outbox: FakeOutboxStore,
        val writer: OutboxOccurrenceWriter,
    ) {
        /** Flipped by the test to model connectivity returning. */
        var online: Boolean = false

        /** Every request that actually reached the "network", in dispatch order. */
        val sent = mutableListOf<OutboxRequest>()

        val sender = object : OutboxRequestSender {
            override suspend fun send(request: OutboxRequest): SendOutcome {
                sent += request
                return if (online) SendOutcome.Success else SendOutcome.Retryable
            }

            override suspend fun sendCreate(request: OutboxRequest): CreateSendOutcome =
                CreateSendOutcome.Terminal // no creates in this test
        }

        val processor = OutboxProcessor(outbox, sender, reconcile = {})
    }

    /**
     * What this device believes the server holds for the firing on [on] — `null` for "no record".
     * Since #390 that is where the optimism lives: the agenda row's `status` is a [WorkingState], an
     * axis with no punctuality and no `missed`, and a firing needs both (ADR-0053 decision 4).
     */
    private suspend fun Scene.recordedState(on: LocalDate = day): OccurrenceResolution? =
        facts.get(ItemKind.Chore, "cho-1-item", on)?.resolution

    @Test
    fun anOfflineMarkClearMarkOnOneFiringReachesTheServerAsExactlyOneWrite() = runTest {
        val scene = scene()

        // --- OFFLINE: the user marks the chore done, undoes it, then marks it done again. ---
        scene.writer.mark("ce-1", OccurrenceAction.Complete)
        assertEquals(OccurrenceResolution.DoneOnTime, scene.recordedState())
        // The undo removes the record entirely — a cleared firing is one the server holds nothing for,
        // which is what lets it go on deriving as Scheduled today and Missed once today has passed.
        scene.writer.clear("ce-1")
        assertNull(scene.recordedState())
        scene.writer.mark("ce-1", OccurrenceAction.Complete)
        assertEquals(OccurrenceResolution.DoneOnTime, scene.recordedState())

        // Three intents queued: `enqueue` appends, always — the merge is the flush's job, deliberately
        // (an enqueue-time merge would race the processor's in-flight send).
        assertEquals(3, scene.outbox.all.size)

        // --- ONLINE: connectivity returns and the driver flushes once. ---
        scene.online = true
        val result = scene.processor.flush(t0)

        // THE acceptance criterion: one server write, and it is the terminal intent's bytes.
        assertEquals(1, scene.sent.size)
        assertEquals(listOf("chores", "cho-1-item", "occurrences", "2026-06-08"), scene.sent.single().path)
        assertEquals("""{"status":"done"}""", scene.sent.single().body)
        assertEquals(2, result.coalesced)
        assertEquals(1, result.succeeded)
        assertEquals(0L, scene.outbox.count())

        // The optimism the user has been looking at all along is untouched by any of it.
        assertEquals(OccurrenceResolution.DoneOnTime, scene.recordedState())
    }

    @Test
    fun aFlushWhileStillOfflineCompactsAnywayAndReplaysByteIdenticalWhenItReturns() = runTest {
        // The flaky-connection shape the issue describes: the app keeps trying while the network is
        // down. The compaction runs on every pass, so the superseded rows are gone after the FIRST
        // failed attempt — they can never dead-letter, because they are never dispatched again.
        val scene = scene()
        scene.writer.mark("ce-1", OccurrenceAction.Complete)
        scene.writer.clear("ce-1")
        scene.writer.mark("ce-1", OccurrenceAction.Complete)

        val offline = scene.processor.flush(t0)
        assertEquals(2, offline.coalesced)
        assertEquals(0, offline.succeeded)
        assertEquals(1, offline.retried)
        assertEquals(1L, scene.outbox.count()) // only the survivor is left to retry
        assertEquals(OccurrenceResolution.DoneOnTime, scene.recordedState()) // optimism stands

        scene.online = true
        val online = scene.processor.flush(t0 + 2.seconds) // past backoff(1) == 1s
        assertEquals(0, online.coalesced) // nothing left to collapse
        assertEquals(1, online.succeeded)
        assertEquals(0L, scene.outbox.count())

        // Two dispatch attempts of ONE write, byte-identical — the ADR-0001 idempotent-replay contract.
        assertEquals(2, scene.sent.size)
        assertEquals(scene.sent[0], scene.sent[1])
    }

    @Test
    fun aRescheduleInTheMiddleIsNeverCollapsedAcross() = runTest {
        // Mark, reschedule to the 10th, mark again. The reschedule is a barrier AND the second mark
        // lands on a different day, so it is a different firing key: all three writes must go out, in
        // order. Collapsing here would silently move a completion onto the wrong date.
        val scene = scene()
        scene.writer.mark("ce-1", OccurrenceAction.Complete)
        scene.writer.reschedule("ce-1", LocalDate(2026, 6, 10))
        assertEquals(LocalDate(2026, 6, 10), scene.calendar.get("ce-1")?.date)
        // The vacated day is recorded as the skip the server writes there, not left as a hole that a
        // past date inside synced coverage would derive into Missed.
        assertEquals(OccurrenceResolution.Skipped, scene.recordedState())
        scene.writer.mark("ce-1", OccurrenceAction.Complete)
        assertEquals(OccurrenceResolution.DoneOnTime, scene.recordedState(LocalDate(2026, 6, 10)))

        scene.online = true
        val result = scene.processor.flush(t0)

        assertEquals(0, result.coalesced)
        assertEquals(3, result.succeeded)
        assertEquals(
            listOf(
                listOf("chores", "cho-1-item", "occurrences", "2026-06-08"),
                listOf("chores", "cho-1-item", "occurrences", "2026-06-08", "reschedule"),
                listOf("chores", "cho-1-item", "occurrences", "2026-06-10"),
            ),
            scene.sent.map { it.path },
        )
    }

    @Test
    fun twoDifferentFiringsOfTheSameChoreBothSurvive() = runTest {
        // The cross-key guarantee at the seam: marking Monday and Tuesday is two firings, not one
        // fidget. A coalescer that keyed on the definition id alone would eat one of them.
        val scene = scene()
        scene.calendar.seed(firing().copy(id = "ce-2", date = LocalDate(2026, 6, 9)))

        scene.writer.mark("ce-1", OccurrenceAction.Complete)
        scene.writer.mark("ce-2", OccurrenceAction.Complete)

        scene.online = true
        val result = scene.processor.flush(t0)

        assertEquals(0, result.coalesced)
        assertEquals(2, result.succeeded)
        assertEquals(
            listOf("2026-06-08", "2026-06-09"),
            scene.sent.map { it.path.last() },
        )
        assertTrue(scene.outbox.all.isEmpty())
    }
}

/**
 * A minimal in-memory [CalendarLocalStore] for the end-to-end write path: a row reads back exactly as
 * written — including its recurring kind, exactly like the real store — so the writer's actionable guard
 * behaves. The observe reads are unused here.
 */
private class FakeCalendarStore : CalendarLocalStore {
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
 * A minimal in-memory [OccurrenceFactLocalStore] keyed exactly as the real table is,
 * `(kind, definitionId, date)` — the same identity [OccurrenceTargets] encodes — so what the writer
 * records and what a reader would look up cannot drift apart in the fake alone.
 */
private class FakeFactStore : OccurrenceFactLocalStore {
    private val rows = mutableMapOf<Triple<ItemKind, String, LocalDate>, OccurrenceFact>()

    private fun key(kind: ItemKind, definitionId: String, date: LocalDate) = Triple(kind, definitionId, date)

    override suspend fun get(kind: ItemKind, definitionId: String, date: LocalDate): OccurrenceFact? =
        rows[key(kind, definitionId, date)]

    override suspend fun upsert(fact: OccurrenceFact) {
        rows[key(fact.kind, fact.definitionId, fact.date)] = fact
    }

    override suspend fun delete(kind: ItemKind, definitionId: String, date: LocalDate) {
        rows.remove(key(kind, definitionId, date))
    }

    override suspend fun transaction(block: suspend (OccurrenceFactLocalStore) -> Unit) = block(this)

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

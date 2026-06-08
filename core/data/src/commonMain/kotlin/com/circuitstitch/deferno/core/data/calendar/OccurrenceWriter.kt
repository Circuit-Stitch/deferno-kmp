package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.outbox.ClearOccurrence
import com.circuitstitch.deferno.core.data.outbox.MarkOccurrence
import com.circuitstitch.deferno.core.data.outbox.OccurrenceMutation
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.RescheduleOccurrence
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import kotlinx.datetime.LocalDate
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The Occurrence (firing-level) **write** seam the Calendar drives (ADR-0001, #74) — the occurrence
 * counterpart to `TaskWriter`/`PlanWriter`. Each call applies optimistically to the cached firing row
 * and enqueues an intent-based, idempotent occurrence mutation for FIFO replay (the per-kind endpoints).
 * These target an **existing** firing, so — unlike create (ADR-0016) — they are offline-first.
 *
 * The act target is the local [CalendarItem] row id (what the agenda holds); the writer resolves the
 * firing's kind + series + date from the cached row, so only an **actionable** firing (a recurring row
 * whose kind resolved) is written — a one-off Task or an unresolved-kind row is a silent no-op (the UI
 * never offers occurrence actions there anyway).
 */
interface OccurrenceWriter {
    /** Mark the firing [itemId] with a coarse [action] (start / complete / skip) — `POST`/`PUT` per kind. */
    suspend fun mark(itemId: String, action: OccurrenceAction)

    /** Clear the firing [itemId]'s status back to Scheduled — the forgiving undo (`DELETE …/{date}`). */
    suspend fun clear(itemId: String)

    /** Reschedule the firing [itemId] to [newDate] (`POST …/{date}/reschedule`; Events only in v1). */
    suspend fun reschedule(itemId: String, newDate: LocalDate)
}

/**
 * The offline-first [OccurrenceWriter] (ADR-0001, #74): optimistic local apply + enqueue, mirroring
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. Each write reads the cached firing,
 * applies the intent's pure transform to the [CalendarItem] (so the agenda updates instantly), then
 * queues the idempotent request for replay. The post-flush reconcile re-pulls the window (LWW), so a
 * transient optimistic state converges on server truth.
 */
class OutboxOccurrenceWriter(
    private val calendarStore: CalendarLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : OccurrenceWriter {

    override suspend fun mark(itemId: String, action: OccurrenceAction) {
        val firing = actionableFiring(itemId) ?: return
        // Defensive: a Habit firing is binary — only Complete is meaningful (the UI offers a habit only
        // Done / Clear). A non-Complete habit action would build `{done:false}`, which *un-completes* the
        // firing — that is the Clear semantic, which must go through [clear] (DELETE), not a mark. The UI
        // already guards this; ignore it here too so a future UI change can't silently un-complete a habit.
        if (firing.kind == ItemKind.Habit && action != OccurrenceAction.Complete) return
        submit(MarkOccurrence(itemId, firing.kind!!, firing.seriesId!!, firing.date, action))
    }

    override suspend fun clear(itemId: String) {
        val firing = actionableFiring(itemId) ?: return
        submit(ClearOccurrence(itemId, firing.kind!!, firing.seriesId!!, firing.date))
    }

    override suspend fun reschedule(itemId: String, newDate: LocalDate) {
        val firing = actionableFiring(itemId) ?: return
        submit(RescheduleOccurrence(itemId, firing.kind!!, firing.seriesId!!, firing.date, newDate))
    }

    /** The cached firing for [itemId], only if it is an actionable occurrence (recurring + kind resolved). */
    private suspend fun actionableFiring(itemId: String): CalendarItem? =
        calendarStore.get(itemId)?.takeIf { it.isActionableOccurrence }

    private suspend fun submit(mutation: OccurrenceMutation) {
        calendarStore.get(mutation.itemId)?.let { calendarStore.upsert(mutation.applyTo(it)) }
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
    }
}

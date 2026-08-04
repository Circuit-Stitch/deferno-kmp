package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.occurrence.OccurrenceFactLocalStore
import com.circuitstitch.deferno.core.data.outbox.ClearOccurrence
import com.circuitstitch.deferno.core.data.outbox.FiringResolutionMutation
import com.circuitstitch.deferno.core.data.outbox.MarkOccurrence
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
 * counterpart to `TaskWriter`/`PlanWriter`. Each call applies optimistically to the cached firing and
 * enqueues an intent-based, idempotent occurrence mutation for FIFO replay (the per-kind endpoints).
 * These target an **existing** firing, so — unlike create (ADR-0016) — they are offline-first.
 *
 * The act target is the local [CalendarItem] row id (what the agenda holds); the writer resolves the
 * firing's kind + addressed item + date from the cached row, so only an **actionable** firing (a
 * recurring row whose kind resolved) is written — a one-off Task or an unresolved-kind row is a silent
 * no-op (the UI never offers occurrence actions there anyway).
 *
 * **The id sent to the endpoints is [CalendarItem.taskId], not [CalendarItem.seriesId] (#380).** The
 * occurrence handlers load the addressed *item* (`load_owned_habit` → `load_item_for_user`) and then
 * resolve the owning Segment themselves from that id + the date (ADR 2026-07-19); a series id loads
 * nothing. `taskId` is non-null on every feed row, so this path carries no `!!`.
 */
interface OccurrenceWriter {
    /** Mark the firing [itemId] with a coarse [action] (start / complete / skip) — `POST`/`PUT` per kind. */
    suspend fun mark(itemId: String, action: OccurrenceAction)

    /** Clear the firing [itemId]'s status back to Scheduled — the forgiving undo (`POST …/{date}/clear`). */
    suspend fun clear(itemId: String)

    /** Reschedule the firing [itemId] to [newDate] (`POST …/{date}/reschedule`; all three kinds). */
    suspend fun reschedule(itemId: String, newDate: LocalDate)
}

/**
 * The offline-first [OccurrenceWriter] (ADR-0001, #74, ADR-0053): optimistic local apply + enqueue,
 * mirroring [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. The post-flush reconcile
 * re-pulls the window (LWW), so a transient optimistic value converges on server truth.
 *
 * **The optimism is written to [factStore], not to the calendar row (#390).** The calendar cache is
 * read here only to resolve the firing the act names — its kind, its addressed item and its date — and
 * is written only by [reschedule], which genuinely moves an agenda row to another day. How a firing
 * *went* is an [com.circuitstitch.deferno.core.model.OccurrenceFact] keyed by the firing identity, and
 * the render-time reading is derived from it; a `CalendarItem.status` could say none of that.
 *
 * Every fact write is wrapped in [OccurrenceFactLocalStore.transaction], because each is a
 * read-modify-write: a mark reads the deadline the firing already had on record before deciding its
 * punctuality, and a reschedule touches two dates. The pre-#390 version of this writer did its
 * read-modify-write un-transacted — alone among the three outbox writers — so a concurrent reconcile
 * could interleave between the read and the write and be overwritten by a decision taken on stale data.
 */
class OutboxOccurrenceWriter(
    private val calendarStore: CalendarLocalStore,
    private val factStore: OccurrenceFactLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : OccurrenceWriter {

    override suspend fun mark(itemId: String, action: OccurrenceAction) {
        val firing = actionableFiring(itemId) ?: return
        // Defensive: a Habit firing is binary — only Complete is meaningful (the UI offers a habit only
        // Done / Clear). A non-Complete habit action would build `{done:false}`, which *un-completes* the
        // firing — that is the Clear semantic, which must go through [clear], not a mark. The UI
        // already guards this; ignore it here too so a future UI change can't silently un-complete a habit.
        if (firing.kind == ItemKind.Habit && action != OccurrenceAction.Complete) return
        submit(MarkOccurrence(itemId, firing.kind!!, firing.taskId, firing.date, action))
    }

    override suspend fun clear(itemId: String) {
        val firing = actionableFiring(itemId) ?: return
        submit(ClearOccurrence(itemId, firing.kind!!, firing.taskId, firing.date))
    }

    override suspend fun reschedule(itemId: String, newDate: LocalDate) {
        val firing = actionableFiring(itemId) ?: return
        // Defensive, and destructive if omitted: a "move" to the day the firing already sits on. The month
        // grid completes an armed reschedule on *any* day-cell tap, including the currently selected one, so
        // this is a single mis-tap away. Origin and destination would then be the SAME (kind, id, date) row,
        // and the two upserts below would run in order against it — origin writes Skipped, destination
        // overwrites it with Scheduled — so a firing the user had already marked Done silently loses its
        // resolution. The queued request is a `400` besides ("new_date must differ from the origin date" is
        // the server's own precondition), which the sender classifies Terminal and dead-letters, so nothing
        // ever retries and nothing converges the destroyed fact back. Refuse it here, the same way [mark]
        // refuses a meaningless habit action.
        if (newDate == firing.date) return
        val mutation = RescheduleOccurrence(itemId, firing.kind!!, firing.taskId, firing.date, newDate)
        // The agenda row moves days; the two firings it moves between each get the resolution the
        // server will write there (origin skipped, destination scheduled — see the intent's KDoc).
        calendarStore.upsert(mutation.applyTo(firing))
        factStore.transaction { store ->
            store.upsert(mutation.originFact(store.get(mutation.kind, mutation.definitionId, mutation.date)))
            store.upsert(mutation.destinationFact(store.get(mutation.kind, mutation.definitionId, newDate)))
        }
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
    }

    /** The cached firing for [itemId], only if it is an actionable occurrence (recurring + kind resolved). */
    private suspend fun actionableFiring(itemId: String): CalendarItem? =
        calendarStore.get(itemId)?.takeIf { it.isActionableOccurrence }

    /**
     * Apply the intent to the firing's fact and queue the request. One instant serves both, so the
     * `done_at` the user sees and the moment the write was queued cannot disagree.
     */
    private suspend fun submit(mutation: FiringResolutionMutation) {
        val at = now()
        factStore.transaction { store ->
            val current = store.get(mutation.kind, mutation.definitionId, mutation.date)
            // A null result is the absence of a record, not "leave it alone" — that is a Clear.
            when (val next = mutation.applyTo(current, at)) {
                null -> store.delete(mutation.kind, mutation.definitionId, mutation.date)
                else -> store.upsert(next)
            }
        }
        outbox.enqueue(mutation.target, mutation.toRequest(), at)
    }
}

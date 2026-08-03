package com.circuitstitch.deferno.core.data.outbox

/**
 * The flush-time occurrence **coalescer** (#396): given an outbox snapshot in [OutboxEntry.seq] order,
 * the seqs a later write has made redundant, for [OutboxProcessor] to delete before it drains. Pure, so
 * the whole truth table below is pinned from `commonTest` on the ADR-0006 JVM-fast path.
 *
 * ## Why this exists
 *
 * [OutboxStore.enqueue] appends, always. Offline, a mark then a clear then a mark of the *same* firing
 * therefore queues three rows and replays as three server writes. On a flaky connection that is pure
 * churn, and every intermediate write is another chance to dead-letter. Occurrence targets are already
 * shaped for the fix: [OccurrenceTargets] is one stable key per firing per day.
 *
 * ## Why at flush, and why delete-only
 *
 * The obvious fix — merge on the way in, at [OutboxStore.enqueue] — races. `enqueue` does not
 * participate in the processor's mutex, so a rewrite landing between the processor's `syncable()`
 * snapshot and its `sender.send` would be sent as the *old* bytes and then deleted, silently destroying
 * the merged write. Closing that properly needs a revision column and a conditional delete, which is a
 * schema migration (ADR-0022). Compacting at the top of [OutboxProcessor.flush], inside the mutex it
 * already holds, has no such race. The race is the whole of the argument, and it is orthogonal to where
 * an entry's [CollapseRole] comes from: this pass does read one persisted column, the declared
 * [OutboxRequest.collapseRole], but a fact each entry states about itself once at enqueue is not the
 * revision-and-conditional-delete machinery an enqueue-time merge would need to be correct.
 *
 * The pass therefore only ever **deletes**. It never rewrites an entry, never re-seqs one, and never
 * moves anything earlier in the queue: the survivor of a collapsed run is always the *latest* entry,
 * keeping its own seq. That single property is what preserves the ADR-0001 FIFO guarantee for every
 * other intent, because deleting elements from a totally-ordered sequence cannot change the relative
 * order of the elements that remain. A queued task edit, plan reorder, settings write, create or comment
 * replays in exactly the position it would have had.
 *
 * It also only fires where it matters. Online, each write flushes immediately and there is nothing to
 * collapse. Offline the queue accumulates, and the next flush compacts it.
 *
 * ## The truth table
 *
 * Key `K` is the `(kind, definitionId, date)` triple [OccurrenceTargets.parse] decodes. A reschedule
 * keys on its **origin** date (it moves the firing to a different date, so the destination day is a
 * different key by construction). Which column of the table an entry falls in is its declared
 * [OutboxRequest.collapseRole] — stated by the intent that built the request and read straight off the
 * row, never re-derived here from the route. `P` is the earlier entry and `S` the later one, on the same
 * key, with no barrier between them:
 *
 * | P \ S | Mark | Clear | Reschedule |
 * |---|---|---|---|
 * | **Mark** | drop P | drop P | keep both |
 * | **Clear** | drop P | drop P | keep both |
 * | **Reschedule** | keep both | keep both | keep both |
 *
 * Everything else keeps both, by construction rather than by a rule:
 *
 * - **Different key** — a different kind, definition id or date never merges. This is what makes a
 *   reschedule chain correct for free: a mark on the new day is a different key and stays after the
 *   reschedule in seq order.
 * - **Not an occurrence target** — a task, plan, item, settings, create or comment entry is never
 *   dropped, and is not a barrier either. It cannot be, because deleting somebody else's row from
 *   around it does not move it. (Those intents declare no role, so they carry the [CollapseRole.Barrier]
 *   default, but the pass never reaches it: [OccurrenceTargets.parse] skips them first.)
 * - **Declared nothing** — an occurrence row queued by a build that predates the declaration, or by a
 *   future intent that forgets to make one, decodes as a [CollapseRole.Barrier] and is simply never
 *   compacted. Failing closed costs one redundant replay; failing open would drop a write.
 * - **Dead-lettered** — invisible. Never dropped (a dead-lettered write is preserved by design, and the
 *   reconcile clobber-guards still read it) and never a barrier (it can never reach the server, so it
 *   cannot separate two writes that can).
 *
 * One habit note: the writer already refuses a non-Complete habit mark, so two of the habit cells are
 * unreachable in production today. They stay in the table, and in the tests, so a future change to that
 * guard cannot quietly change replay semantics.
 *
 * ## The consequence worth stating
 *
 * `LedgerRecordingOutboxStore` records an Activity row on every enqueue, before any compaction can see
 * it. A coalesced-away write therefore keeps its local ledger row, unacknowledged, until it is pruned.
 * That is deliberate: the user really did perform the action, so the row is honest, and the alternative
 * — compacting above the ledger decorator — would silently delete Activity rows instead.
 */
internal fun coalesceOccurrences(entries: List<OutboxEntry>): List<Long> {
    val superseded = mutableListOf<Long>()
    // key -> the seq of the latest still-collapsible entry seen for it. A barrier clears the slot, which
    // is what splits the key's run: whatever follows compacts among itself, not across the barrier.
    val open = mutableMapOf<OccurrenceTarget, Long>()

    for (entry in entries) {
        // Dead-lettered rows are invisible. The processor passes `syncable()` (which already excludes
        // them), but filtering here too makes the function correct for any snapshot it is handed.
        if (entry.failedAt != null) continue
        val key = OccurrenceTargets.parse(entry.target) ?: continue
        when (entry.request.collapseRole) {
            CollapseRole.Absolute -> open.put(key, entry.seq)?.let { superseded += it }
            CollapseRole.Barrier -> open.remove(key)
        }
    }

    // Ascending, so the caller deletes (and logs) in queue order regardless of key interleaving.
    return superseded.sorted()
}

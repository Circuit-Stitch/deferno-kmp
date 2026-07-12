package com.circuitstitch.deferno.core.data.outbox

import kotlin.time.Instant

/**
 * One queued, not-yet-dispatched write (ADR-0001, #23) — a persisted FIFO entry pairing the verbatim
 * [request] to replay with its replay bookkeeping.
 *
 * - [seq] — the monotonic FIFO key (a never-reused `AUTOINCREMENT` id), so replay order can never
 *   regress below an already-dispatched entry even after the head is deleted.
 * - [attempts] — how many times a *retryable* failure has been seen (0 for a fresh entry).
 * - [nextAttemptAt] — the entry is *ready* to dispatch when this is `<= now`; a retry pushes it into
 *   the future by the backoff (the head-of-line wait).
 * - [createdAt] — when the intent was enqueued (audit/diagnostics).
 * - [failedAt] — the dead-letter marker (`null` = live). Set when the server terminally rejected the
 *   write (or its retries were exhausted): the entry is preserved (its outbound request is never
 *   silently dropped) but it is excluded from [OutboxStore.syncable] so the processor never replays it
 *   again. It stays in the queue (visible via [OutboxStore.allUnsynced]) so the reconcile clobber-guards
 *   can still see it.
 */
data class OutboxEntry(
    val seq: Long,
    val target: String,
    val request: OutboxRequest,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
    val failedAt: Instant? = null,
)

/**
 * The local source-of-truth port for the outbox queue (ADR-0001, #23). The write path enqueues
 * through [enqueue]; the [OutboxProcessor] drains via [syncable] (FIFO, live only) and resolves entries
 * with [delete] (dispatched OK), [markRetry] (transient failure → backoff), or [markFailed] (terminal
 * rejection → dead-letter, preserved not deleted). Extracting persistence behind a port keeps the
 * replay/backoff/head-of-line *algorithm* (the heart of #23) unit-testable against an in-memory fake
 * on the ADR-0006 JVM-fast path, while [SqlDelightOutboxStore] proves the real SQLite path.
 *
 * Two read views, by intent: [syncable] is the live work the processor drains and the settings guard
 * reads (a dead-lettered write is excluded, so it stops replaying AND stops blocking the settings
 * refresh); [allUnsynced] is the full queue the comment clobber-guard and the id-heal re-point read
 * (they must still see a dead-lettered row to keep protecting / re-pointing the local row behind it).
 */
interface OutboxStore {

    /**
     * Appends [request] to the tail of the queue, targeting [target], enqueued at [now] (a fresh
     * entry starts at `attempts = 0` and is immediately ready: `nextAttemptAt = now`).
     *
     * [before] is **audit-only** metadata for the activity ledger (#260): the pre-apply old-value JSON of
     * the fields this write changes, snapshotted by the caller before its optimistic apply so the Activity
     * feed can show a true old->new diff. The replay queue ignores it entirely (it is never persisted on
     * the outbox row nor replayed); only the [LedgerRecordingOutboxStore] decorator forwards it to the
     * ledger. Defaulted null — a caller that captures no pre-image (or any non-diffing write) omits it.
     */
    suspend fun enqueue(target: String, request: OutboxRequest, now: Instant, before: String? = null)

    /**
     * The **still-syncable** queue — live rows only (dead-lettered [OutboxEntry.failedAt] rows excluded)
     * — in FIFO ([OutboxEntry.seq]) order. The processor drains this head-first, so a dead-lettered entry
     * never replays again (no drain-loop filter needed); the settings reconcile guard reads it too, so a
     * terminally-rejected settings write stops blocking the refresh.
     */
    suspend fun syncable(): List<OutboxEntry>

    /**
     * The **whole** queue (live + dead-lettered) in FIFO ([OutboxEntry.seq]) order — read by the comment
     * reconcile clobber-guard and the [repointId] id-heal, both of which must still see a dead-lettered
     * row to keep protecting / re-pointing the optimistic local row it stands behind.
     */
    suspend fun allUnsynced(): List<OutboxEntry>

    /** Removes the successfully-dispatched entry [seq] from the queue. */
    suspend fun delete(seq: Long)

    /**
     * Dead-letter the terminally-rejected / attempts-exhausted entry [seq] at [failedAt]: it is kept
     * (its outbound request is preserved, never silently dropped) but excluded from [syncable] so the
     * processor never replays it again. It stays visible via [allUnsynced], so the optimistic local row it
     * stands behind stays protected by the guards.
     */
    suspend fun markFailed(seq: Long, failedAt: Instant)

    /** Records a retryable failure on [seq]: the new [attempts] count and the backed-off [nextAttemptAt]. */
    suspend fun markRetry(seq: Long, attempts: Int, nextAttemptAt: Instant)

    /**
     * Rewrites the [target] + [request] of the queued entry [seq] **in place** (#185, id-heal),
     * preserving its FIFO [OutboxEntry.seq]. Used to re-point a queued mutation from an offline-created
     * Item's client id to the server's canonical id when the two diverge.
     */
    suspend fun update(seq: Long, target: String, request: OutboxRequest)

    /** The size of the still-syncable queue — live entries only (dead-lettered rows excluded, like [syncable]). */
    suspend fun count(): Long
}

/**
 * Re-point every queued entry mentioning [from] to [to] **in place** (preserving FIFO [OutboxEntry.seq]),
 * so a queued mutation addressing an offline-minted client id lands on the server's canonical id once the
 * two diverge — the #185 item-create id-heal and the ADR-0043 comment-create rekey are the same substring
 * re-point. A UUID substring replace is collision-safe (ids never appear as substrings of unrelated
 * content). Returns `true` if any entry changed, so a caller walking an `allUnsynced()` snapshot knows
 * the queue moved and its snapshot is now stale.
 */
internal suspend fun OutboxStore.repointId(from: String, to: String): Boolean {
    var changed = false
    for (entry in allUnsynced()) {
        val newTarget = entry.target.replace(from, to)
        val newPath = entry.request.path.map { it.replace(from, to) }
        val newBody = entry.request.body?.replace(from, to)
        if (newTarget != entry.target || newPath != entry.request.path || newBody != entry.request.body) {
            update(entry.seq, newTarget, entry.request.copy(path = newPath, body = newBody))
            changed = true
        }
    }
    return changed
}

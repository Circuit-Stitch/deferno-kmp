package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import kotlin.time.Instant

/**
 * The single choke-point that feeds the offline-first activity ledger (#260): an [OutboxStore] decorator
 * that records every enqueued write into the [ledger] before delegating, so the Activity feed captures
 * EVERY mutation the app makes with no per-writer edits — a new write path that enqueues is logged for
 * free, and one cannot be forgotten. Every write funnels through [enqueue] just after its optimistic
 * local apply (ADR-0001), so the ledger entry is written at apply-time and shows instantly, independent
 * of whether/when the write later dispatches.
 *
 * Records as [ActivitySource.Mobile] — these are local app-side writes. Server-sourced ("via Website" /
 * "via MCP") entries are a follow-up, recorded at the snapshot-reconcile seam, not here.
 *
 * The ledger write is best-effort: a ledger failure must never lose or block the user's actual write, so
 * it is swallowed (the outbox enqueue — the durable, replayed source of truth — has already succeeded).
 */
class LedgerRecordingOutboxStore(
    private val delegate: OutboxStore,
    private val ledger: ActivityLedgerStore,
) : OutboxStore {

    override suspend fun enqueue(target: String, request: OutboxRequest, now: Instant, before: String?) {
        delegate.enqueue(target, request, now, before)
        runCatching { ledger.record(ActivitySource.Mobile, target, request, before, now) }
    }

    override suspend fun syncable(): List<OutboxEntry> = delegate.syncable()

    override suspend fun allUnsynced(): List<OutboxEntry> = delegate.allUnsynced()

    override suspend fun delete(seq: Long) = delegate.delete(seq)

    override suspend fun markFailed(seq: Long, failedAt: Instant) = delegate.markFailed(seq, failedAt)

    override suspend fun markRetry(seq: Long, attempts: Int, nextAttemptAt: Instant) =
        delegate.markRetry(seq, attempts, nextAttemptAt)

    override suspend fun update(seq: Long, target: String, request: OutboxRequest) =
        delegate.update(seq, target, request)

    override suspend fun count(): Long = delegate.count()
}

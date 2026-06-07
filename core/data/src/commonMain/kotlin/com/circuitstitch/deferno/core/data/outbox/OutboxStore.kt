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
 */
data class OutboxEntry(
    val seq: Long,
    val target: String,
    val request: OutboxRequest,
    val attempts: Int,
    val nextAttemptAt: Instant,
    val createdAt: Instant,
)

/**
 * The local source-of-truth port for the outbox queue (ADR-0001, #23). The write path enqueues
 * through [enqueue]; the [OutboxProcessor] drains via [pending] (FIFO) and resolves entries with
 * [delete] (dispatched) or [markRetry] (backoff). Extracting persistence behind a port keeps the
 * replay/backoff/head-of-line *algorithm* (the heart of #23) unit-testable against an in-memory fake
 * on the ADR-0006 JVM-fast path, while [SqlDelightOutboxStore] proves the real SQLite path.
 */
interface OutboxStore {

    /**
     * Appends [request] to the tail of the queue, targeting [target], enqueued at [now] (a fresh
     * entry starts at `attempts = 0` and is immediately ready: `nextAttemptAt = now`).
     */
    suspend fun enqueue(target: String, request: OutboxRequest, now: Instant)

    /** The whole queue in FIFO ([OutboxEntry.seq]) order — the processor walks this head-first. */
    suspend fun pending(): List<OutboxEntry>

    /** Removes the dispatched/abandoned entry [seq] from the queue. */
    suspend fun delete(seq: Long)

    /** Records a retryable failure on [seq]: the new [attempts] count and the backed-off [nextAttemptAt]. */
    suspend fun markRetry(seq: Long, attempts: Int, nextAttemptAt: Instant)

    /** The number of pending entries — the size of the unsynced-writes queue. */
    suspend fun count(): Long
}

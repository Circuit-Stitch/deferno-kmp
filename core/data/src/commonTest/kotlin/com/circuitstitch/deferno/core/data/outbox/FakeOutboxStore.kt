package com.circuitstitch.deferno.core.data.outbox

import kotlin.time.Instant

/**
 * In-memory [OutboxStore] for the replay-engine tests (#23, ADR-0006 JVM-fast path). A list of entries
 * with a monotonically-increasing [nextSeq] that is **never reused** — mirroring the SQLite
 * `AUTOINCREMENT` guarantee the FIFO order relies on, so a `delete` of the head can't let a later
 * enqueue reuse a smaller seq. [SqlDelightOutboxStore] proves the real SQL path separately.
 */
class FakeOutboxStore(initial: List<OutboxEntry> = emptyList()) : OutboxStore {

    private val entries = initial.toMutableList()
    private var nextSeq = (initial.maxOfOrNull { it.seq } ?: 0L) + 1

    /** Direct read of the backing queue for assertions (already in seq order via [pending]). */
    val all: List<OutboxEntry> get() = entries.sortedBy { it.seq }

    override suspend fun enqueue(target: String, request: OutboxRequest, now: Instant) {
        entries += OutboxEntry(
            seq = nextSeq++,
            target = target,
            request = request,
            attempts = 0,
            nextAttemptAt = now,
            createdAt = now,
        )
    }

    override suspend fun pending(): List<OutboxEntry> = entries.sortedBy { it.seq }

    override suspend fun delete(seq: Long) {
        entries.removeAll { it.seq == seq }
    }

    override suspend fun markFailed(seq: Long, failedAt: Instant) {
        val index = entries.indexOfFirst { it.seq == seq }
        if (index >= 0) entries[index] = entries[index].copy(failedAt = failedAt)
    }

    override suspend fun markRetry(seq: Long, attempts: Int, nextAttemptAt: Instant) {
        val index = entries.indexOfFirst { it.seq == seq }
        if (index >= 0) entries[index] = entries[index].copy(attempts = attempts, nextAttemptAt = nextAttemptAt)
    }

    override suspend fun update(seq: Long, target: String, request: OutboxRequest) {
        val index = entries.indexOfFirst { it.seq == seq }
        if (index >= 0) entries[index] = entries[index].copy(target = target, request = request)
    }

    // Live (still-syncable) queue size — dead-lettered entries are preserved but excluded, mirroring the
    // SQL `count`'s `WHERE failed_at IS NULL`.
    override suspend fun count(): Long = entries.count { it.failedAt == null }.toLong()
}

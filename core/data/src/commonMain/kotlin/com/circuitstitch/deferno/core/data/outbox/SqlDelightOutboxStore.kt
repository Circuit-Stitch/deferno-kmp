package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlin.time.Instant
import com.circuitstitch.deferno.core.database.sql.OutboxEntry as OutboxRow

/**
 * The production [OutboxStore] over the SQLDelight [DefernoDatabase] (ADR-0001, #23). The thin
 * SQL ↔ domain plumbing between the adapter-free `outboxEntry` rows (#23 schema) and the domain
 * [OutboxEntry]; the replay/backoff *policy* lives in [OutboxProcessor] (proved against the in-memory
 * fake), while *this* class's only job is the row mapping — proved by the real-SQLite
 * `SqlDelightOutboxStoreTest`, which also pins the `AUTOINCREMENT` monotonic-seq guarantee.
 *
 * Every query is a synchronous SQLDelight call (no observe `Flow` — the processor pulls [pending] on
 * demand), so the suspend port methods run straight through. Encoding mirrors the `taskEntity`
 * conventions: `path` segments ↔ a `\n`-joined TEXT, instants ↔ RFC3339 strings, the enum method ↔
 * its `.name` decoded **defensively** (an unrecognised stored token degrades rather than throwing).
 */
class SqlDelightOutboxStore(
    private val db: DefernoDatabase,
) : OutboxStore {

    private val queries get() = db.outboxEntryQueries

    override suspend fun enqueue(target: String, request: OutboxRequest, now: Instant) {
        queries.enqueue(
            target = target,
            method = request.method.name,
            path = request.path.joinToString("\n"),
            body = request.body,
            attempts = 0L,
            next_attempt_at = now.toString(),
            created_at = now.toString(),
        )
    }

    override suspend fun pending(): List<OutboxEntry> =
        queries.selectAllInOrder().executeAsList().map { it.toDomain() }

    override suspend fun delete(seq: Long) {
        queries.deleteBySeq(seq)
    }

    override suspend fun markRetry(seq: Long, attempts: Int, nextAttemptAt: Instant) {
        queries.markRetry(attempts.toLong(), nextAttemptAt.toString(), seq)
    }

    override suspend fun count(): Long = queries.count().executeAsOne()
}

/** Decodes a stored `outboxEntry` row into the domain [OutboxEntry]. Defensive on the method column. */
private fun OutboxRow.toDomain(): OutboxEntry = OutboxEntry(
    seq = seq,
    target = target,
    request = OutboxRequest(
        method = method.toOutboxMethodOrDefault(),
        path = if (path.isEmpty()) emptyList() else path.split("\n"),
        body = body,
    ),
    attempts = attempts.toInt(),
    nextAttemptAt = Instant.parse(next_attempt_at),
    createdAt = Instant.parse(created_at),
)

/** Defensive decode: an unrecognised stored method token degrades to [OutboxMethod.Post] (never throws). */
private fun String.toOutboxMethodOrDefault(): OutboxMethod =
    OutboxMethod.entries.firstOrNull { it.name == this } ?: OutboxMethod.Post

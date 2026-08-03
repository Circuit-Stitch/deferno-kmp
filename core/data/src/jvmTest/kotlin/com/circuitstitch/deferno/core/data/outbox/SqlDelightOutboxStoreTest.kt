package com.circuitstitch.deferno.core.data.outbox

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-SQLite integration test for the outbox store (#23, ADR-0006 JVM-fast path). The commonTest
 * fakes prove the replay *algorithm*; this proves the *SQL path* — that [SqlDelightOutboxStore]'s
 * row ↔ domain mapping round-trips through a genuine `DefernoDatabase` over an in-memory
 * `JdbcSqliteDriver`, including the bodiless-DELETE (`body IS NULL`), the declared
 * [OutboxRequest.collapseRole] the flush-time coalescer reads back off the row (#396), and the
 * **`AUTOINCREMENT` monotonic-seq guarantee** the FIFO replay order depends on.
 */
class SqlDelightOutboxStoreTest {

    private val t0 = Instant.parse("2026-06-07T12:00:00Z")
    private val t1 = Instant.parse("2026-06-07T12:00:01Z")
    private val t2 = Instant.parse("2026-06-07T12:00:02Z")

    private fun newDatabase(): DefernoDatabase = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun newStore(): SqlDelightOutboxStore = SqlDelightOutboxStore(newDatabase())

    @Test
    fun enqueueAndPendingRoundTripEntriesInSeqOrder() = runTest {
        val store = newStore()
        store.enqueue("task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"status":"done"}"""), t0)
        store.enqueue("plan:d", OutboxRequest(OutboxMethod.Post, listOf("tasks", "plan", "add"), """{"task_id":"t1"}"""), t1)
        store.enqueue("task:b", OutboxRequest(OutboxMethod.Delete, listOf("tasks", "b")), t2)

        val pending = store.allUnsynced()
        assertEquals(3, pending.size)
        assertEquals(pending.map { it.seq }.sorted(), pending.map { it.seq }) // FIFO seq order

        val first = pending[0]
        assertEquals("task:a", first.target)
        assertEquals(OutboxMethod.Patch, first.request.method)
        assertEquals(listOf("tasks", "a"), first.request.path)
        assertEquals("""{"status":"done"}""", first.request.body)
        assertEquals(0, first.attempts)
        assertEquals(t0, first.nextAttemptAt)
        assertEquals(t0, first.createdAt)

        // The bodiless DELETE round-trips with a NULL body.
        val deleteEntry = pending[2]
        assertEquals(OutboxMethod.Delete, deleteEntry.request.method)
        assertNull(deleteEntry.request.body)
        assertEquals(3L, store.count())
    }

    @Test
    fun deleteRemovesAndMarkRetryUpdatesBackoffState() = runTest {
        val store = newStore()
        store.enqueue("task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"x"}"""), t0)
        store.enqueue("task:b", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "b"), """{"title":"y"}"""), t0)

        val first = store.allUnsynced().first()
        store.markRetry(first.seq, attempts = 3, nextAttemptAt = t2)
        val retried = store.allUnsynced().first { it.seq == first.seq }
        assertEquals(3, retried.attempts)
        assertEquals(t2, retried.nextAttemptAt)

        store.delete(first.seq)
        assertEquals(1L, store.count())
        assertTrue(store.allUnsynced().none { it.seq == first.seq })
    }

    @Test
    fun markFailedDeadLettersTheRowKeepingItInAllUnsyncedButOutOfTheSyncableViewAndLiveCount() = runTest {
        val store = newStore()
        store.enqueue("comment-create:t-1:c", OutboxRequest(OutboxMethod.Post, listOf("tasks", "t-1", "comments"), """{"body":"hi"}"""), t0)
        store.enqueue("task:b", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "b"), """{"title":"y"}"""), t0)

        val create = store.allUnsynced().first()
        store.markFailed(create.seq, t1)

        // Preserved (never deleted) and still visible via allUnsynced() so the comment clobber-guard keeps
        // protecting its optimistic row — but flagged failed, dropped from the syncable() drain view (so
        // the processor never replays it), and excluded from the live (syncable) count.
        val row = store.allUnsynced().first { it.seq == create.seq }
        assertEquals(t1, row.failedAt)
        assertEquals(2, store.allUnsynced().size)
        assertEquals(listOf("task:b"), store.syncable().map { it.target }) // the dead-lettered row is gone from the drain view
        assertEquals(1L, store.count()) // only the live `task:b` counts
    }

    @Test
    fun theDeclaredCollapseRoleSurvivesTheRoundTripThroughSqlite() = runTest {
        val store = newStore()
        store.enqueue(
            "occurrence:Chore:cho-1-item:2026-06-08",
            OutboxRequest(
                OutboxMethod.Put,
                listOf("chores", "cho-1-item", "occurrences", "2026-06-08"),
                """{"status":"done"}""",
                acceptsActivityStamp = true,
                collapseRole = CollapseRole.Absolute,
            ),
            t0,
        )
        store.enqueue("task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), "{}"), t1)

        val (occurrence, task) = store.allUnsynced()
        // The whole point of the column: the coalescer runs at flush, over rows read back out of the
        // database, so the role the intent declared has to survive the write and the read.
        assertEquals(CollapseRole.Absolute, occurrence.request.collapseRole)
        // Every row is self-describing — a write that declared nothing round-trips as the fail-closed
        // default rather than as a NULL the reader has to interpret.
        assertEquals(CollapseRole.Barrier, task.request.collapseRole)
        // The deliberate contrast, enqueued `true` on the same request: `acceptsActivityStamp` is NOT a
        // column, because it is only ever asked at enqueue — by read time the stamp is already inside
        // `body`, which replay re-sends verbatim. So it comes back false, and that is correct.
        assertFalse(occurrence.request.acceptsActivityStamp)
    }

    @Test
    fun aRowQueuedBeforeTheCollapseRoleColumnExistedDecodesAsABarrier() = runTest {
        // Migration 18->19 adds `collapse_role` with no back-fill, so every entry already in the queue on
        // upgrade carries a NULL. `SqlDelightOutboxStore.enqueue` can never produce that shape (it always
        // writes a name), so the generated query is driven directly — the only way to build the row a
        // pre-migration build left behind.
        val db = newDatabase()
        db.outboxEntryQueries.enqueue(
            target = "occurrence:Chore:cho-1-item:2026-06-08",
            method = OutboxMethod.Put.name,
            path = listOf("chores", "cho-1-item", "occurrences", "2026-06-08").joinToString("\n"),
            body = """{"status":"done"}""",
            attempts = 0L,
            next_attempt_at = t0.toString(),
            created_at = t0.toString(),
            collapse_role = null,
        )

        val entry = SqlDelightOutboxStore(db).allUnsynced().single()
        assertEquals(OutboxMethod.Put, entry.request.method)
        assertEquals(listOf("chores", "cho-1-item", "occurrences", "2026-06-08"), entry.request.path)
        // NULL means only "queued before the column existed". It must fail closed: an old row replays
        // uncompacted, rather than being read as an absolute write that silently eats its neighbours.
        assertEquals(CollapseRole.Barrier, entry.request.collapseRole)
    }

    @Test
    fun seqIsMonotonicAndNeverReusedAcrossDeletes() = runTest {
        val store = newStore()
        store.enqueue("task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), "{}"), t0)
        store.enqueue("task:b", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "b"), "{}"), t0)
        store.enqueue("task:c", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "c"), "{}"), t0)
        val before = store.allUnsynced().map { it.seq }

        // Drain the whole queue, then enqueue a fresh entry.
        before.forEach { store.delete(it) }
        store.enqueue("task:d", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "d"), "{}"), t0)

        val dSeq = store.allUnsynced().single().seq
        // AUTOINCREMENT: the new seq is strictly greater than every prior one — never recycled — so a
        // later enqueue can never sort ahead of an already-deleted earlier entry (FIFO integrity).
        assertTrue(dSeq > before.max(), "expected $dSeq > ${before.max()}")
    }
}

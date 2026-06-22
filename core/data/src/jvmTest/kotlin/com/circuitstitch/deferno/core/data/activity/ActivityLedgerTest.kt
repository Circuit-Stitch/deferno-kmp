package com.circuitstitch.deferno.core.data.activity

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.SqlDelightOutboxStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The offline-first activity ledger (#260): proves the single choke-point ([LedgerRecordingOutboxStore])
 * records EVERY write into the durable ledger at apply-time, reverse-chronologically and tagged local,
 * over a real in-memory `DefernoDatabase` (ADR-0006 JVM-fast path), plus the read-time summary/deep-link
 * derivations for each target shape.
 */
class ActivityLedgerTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")
    private val t1 = Instant.parse("2026-06-21T12:00:01Z")
    private val t2 = Instant.parse("2026-06-21T12:00:02Z")

    private fun newDb() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    @Test
    fun decoratorRecordsEveryEnqueueReverseChronAsLocal() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        val outbox = LedgerRecordingOutboxStore(SqlDelightOutboxStore(db), ledger)

        outbox.enqueue("task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"x"}"""), t0)
        outbox.enqueue("create:Task:b", OutboxRequest(OutboxMethod.Post, listOf("tasks"), """{"id":"b"}"""), t1)
        outbox.enqueue("task:a", OutboxRequest(OutboxMethod.Delete, listOf("tasks", "a")), t2)

        // The durable journal mirrors all three, newest first, every one tagged the local source.
        val feed = ledger.recent().first()
        assertEquals(3, feed.size)
        assertEquals(listOf(t2, t1, t0), feed.map { it.recordedAt })
        assertTrue(feed.all { it.source == ActivitySource.Mobile })
        assertEquals(listOf("Deleted a task", "Created a task", "Updated a task"), feed.map { it.summary() })

        // The outbox queue itself is unaffected — the decorator delegated the real enqueue.
        assertEquals(3L, outbox.count())
    }

    @Test
    fun clearEmptiesLedger() = runTest {
        val db = newDb()
        val ledger = SqlDelightActivityLedgerStore(db, Dispatchers.Unconfined)
        ledger.record(ActivitySource.Mobile, "task:a", OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), "{}"), t0)
        assertEquals(1, ledger.recent().first().size)
        ledger.clear()
        assertTrue(ledger.recent().first().isEmpty())
    }

    @Test
    fun summaryAndItemIdCoverEveryTargetShape() {
        fun entry(target: String, method: OutboxMethod) =
            ActivityEntry(seq = 1, recordedAt = t0, source = ActivitySource.Mobile, target = target, method = method, path = emptyList())

        assertEquals("Updated a task", entry("task:abc", OutboxMethod.Patch).summary())
        assertEquals("Deleted a task", entry("task:abc", OutboxMethod.Delete).summary())
        assertEquals("Created a habit", entry("create:Habit:h1", OutboxMethod.Post).summary())
        assertEquals("Moved an item", entry("item:i1", OutboxMethod.Patch).summary())
        assertEquals("Updated your plan", entry("plan:2026-06-21:UTC", OutboxMethod.Post).summary())
        assertEquals("Changed settings", entry("settings", OutboxMethod.Patch).summary())
        assertEquals("Updated a event occurrence", entry("occurrence:Event:s1:2026-06-21", OutboxMethod.Patch).summary())
        assertEquals("Cleared a event occurrence", entry("occurrence:Event:s1:2026-06-21", OutboxMethod.Delete).summary())
        assertEquals("Updated an item", entry("weird:thing", OutboxMethod.Patch).summary())

        assertEquals("abc", entry("task:abc", OutboxMethod.Patch).itemId())
        assertEquals("i1", entry("item:i1", OutboxMethod.Patch).itemId())
        assertEquals("h1", entry("create:Habit:h1", OutboxMethod.Post).itemId())
        assertNull(entry("plan:2026-06-21:UTC", OutboxMethod.Post).itemId())
        assertNull(entry("settings", OutboxMethod.Patch).itemId())
    }

    @Test
    fun unknownSourceTokenDegradesRatherThanThrows() {
        assertEquals(ActivitySource.Mobile, ActivitySource.fromToken("Mobile"))
        assertEquals(ActivitySource.Unknown, ActivitySource.fromToken("Telepathy"))
    }
}

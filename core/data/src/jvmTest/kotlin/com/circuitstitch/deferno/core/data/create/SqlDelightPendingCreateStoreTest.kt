package com.circuitstitch.deferno.core.data.create

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-SQLite integration test for the offline-create side table (#185, ADR-0006 JVM-fast path).
 * The commonTest fake proves the lifecycle *logic*; this proves the *SQL path* — that
 * [SqlDelightPendingCreateStore]'s add/confirm/rekey/reject/read round-trips through a genuine
 * `DefernoDatabase` over an in-memory `JdbcSqliteDriver`.
 */
class SqlDelightPendingCreateStoreTest {

    private fun newStore(): SqlDelightPendingCreateStore {
        val db = DefernoDatabase(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
        )
        return SqlDelightPendingCreateStore(db)
    }

    @Test
    fun addRecordsAPendingRowReadableByPendingIdsAndAll() = runTest {
        val store = newStore()
        store.add("c1", ItemKind.Task)
        store.add("c2", ItemKind.Habit)

        assertEquals(setOf("c1", "c2"), store.pendingIds())
        val all = store.all().associateBy { it.itemId }
        assertEquals(ItemKind.Habit, all.getValue("c2").itemKind)
        assertEquals(PendingCreateState.Pending, all.getValue("c1").state)
        assertNull(all.getValue("c1").canonicalId)
    }

    @Test
    fun confirmFlipsStateAndRecordsCanonicalIdAndDropsItFromPending() = runTest {
        val store = newStore()
        store.add("c1", ItemKind.Task)

        store.confirm("c1", canonicalId = "c1")

        assertTrue(store.pendingIds().isEmpty()) // confirmed rows aren't pending
        val row = store.all().single()
        assertEquals(PendingCreateState.Confirmed, row.state)
        assertEquals("c1", row.canonicalId)
    }

    @Test
    fun rekeyMovesTheRowToTheNewId() = runTest {
        val store = newStore()
        store.add("client", ItemKind.Task)

        store.rekey(fromId = "client", toId = "server")

        assertEquals(setOf("server"), store.pendingIds())
        assertEquals("server", store.all().single().itemId)
    }

    @Test
    fun rejectDropsTheRow() = runTest {
        val store = newStore()
        store.add("c1", ItemKind.Task)

        store.reject("c1")

        assertTrue(store.all().isEmpty())
    }
}

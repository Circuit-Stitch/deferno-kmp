package com.circuitstitch.deferno.core.data.task

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-SQLite integration test (#22, ADR-0006 JVM-fast path). The commonTest fakes prove the
 * reconcile/hydration *algorithm*; this proves the *SQL path* — that [SqlDelightTaskLocalStore]'s
 * row<->domain mapping, observe-via-Flow, and `db.transaction { }` atomicity round-trip through a
 * genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`, and that a full
 * [OfflineTaskRepository.refresh] reconcile commits through real SQLite end to end.
 */
class SqlDelightTaskLocalStoreTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun newStore(): SqlDelightTaskLocalStore {
        val db = DefernoDatabase(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
        )
        return SqlDelightTaskLocalStore(db, Dispatchers.Default)
    }

    private fun summary(id: String, title: String = "task-$id", sequence: Long = 1, deletedAt: Instant? = null) =
        Task(
            id = TaskId(id),
            orgSlug = "u-e4h2qk",
            title = title,
            workingState = WorkingState.Open,
            sequence = sequence,
            dateCreated = created,
            deletedAt = deletedAt,
            hydration = HydrationState.Summary,
        )

    private fun full(id: String) = summary(id).copy(
        hydration = HydrationState.Full,
        labels = listOf("home", "urgent"),
        children = listOf(TaskId("c1"), TaskId("c2")),
        ownerOrgId = OrgId("org-$id"),
        description = "body-$id",
        nextTaskId = TaskId("next-$id"),
        finishedAt = Instant.parse("2026-06-02T10:00:00Z"),
        pinned = true,
    )

    @Test
    fun upsertAndGetRoundTripsAFullTaskThroughRealSqlite() = runTest {
        val store = newStore()
        val task = full("a")

        store.upsert(task)

        assertEquals(task, store.get(TaskId("a")))
    }

    @Test
    fun upsertReplacesByIdAndDeleteRemoves() = runTest {
        val store = newStore()
        store.upsert(summary("a", title = "before"))
        store.upsert(summary("a", title = "after"))
        assertEquals("after", store.get(TaskId("a"))?.title)
        assertEquals(setOf(TaskId("a")), store.allIds())

        store.delete(TaskId("a"))
        assertNull(store.get(TaskId("a")))
        assertTrue(store.allIds().isEmpty())
    }

    @Test
    fun observeActiveExcludesTombstonesAndOrdersBySequence() = runTest {
        val store = newStore()
        store.upsert(summary("a", sequence = 2))
        store.upsert(summary("b", sequence = 1))
        store.upsert(summary("gone", sequence = 3, deletedAt = Instant.parse("2026-06-01T00:00:00Z")))

        store.observeActive().test {
            val active = awaitItem()
            assertEquals(listOf(TaskId("b"), TaskId("a")), active.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        // Tombstone kept in the full table (reconcile idempotence).
        assertTrue(store.allIds().contains(TaskId("gone")))
    }

    @Test
    fun observeReEmitsOnceForAReconcileTransaction() = runTest {
        val store = newStore()
        store.observeActive().test {
            assertTrue(awaitItem().isEmpty())
            store.transaction { tx ->
                tx.upsert(summary("a", sequence = 2))
                tx.upsert(summary("b", sequence = 1))
            }
            // A single commit-time emission carrying both rows (not one per upsert).
            assertEquals(listOf(TaskId("b"), TaskId("a")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun fullRefreshReconcilesThroughRealSqlite() = runTest {
        val store = newStore()
        // Seed: a row that will survive, a row that will vanish, and a hydrated row.
        store.upsert(summary("keep", title = "old"))
        store.upsert(summary("vanished"))
        store.upsert(full("hydrated"))

        val remote = FakeTaskRemoteSource(
            snapshot = listOf(
                summary("keep", title = "new"),
                summary("fresh"),
                // a summary refresh of the hydrated row (must NOT downgrade it)
                summary("hydrated", title = "hydrated-renamed"),
                summary("tomb", deletedAt = Instant.parse("2026-06-01T00:00:00Z")),
            ),
        )
        val repo = OfflineTaskRepository(store, remote)

        repo.refresh()

        // keep updated, fresh inserted, vanished purged.
        assertEquals("new", store.get(TaskId("keep"))?.title)
        assertEquals(WorkingState.Open, store.get(TaskId("fresh"))?.workingState)
        assertNull(store.get(TaskId("vanished")))
        // tombstone present + isDeleted, excluded from active.
        assertTrue(store.get(TaskId("tomb"))?.isDeleted == true)
        // hydration preserved across a summary refresh.
        val hydrated = store.get(TaskId("hydrated"))
        assertEquals(HydrationState.Full, hydrated?.hydration)
        assertEquals("body-hydrated", hydrated?.description)
        assertEquals("hydrated-renamed", hydrated?.title)

        store.observeActive().test {
            val activeIds = awaitItem().map { it.id }.toSet()
            assertEquals(setOf(TaskId("keep"), TaskId("fresh"), TaskId("hydrated")), activeIds)
            assertFalse(activeIds.contains(TaskId("tomb")))
            cancelAndIgnoreRemainingEvents()
        }
    }
}

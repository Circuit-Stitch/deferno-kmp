package com.circuitstitch.deferno.core.data.task

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.item.FakeItemSnapshotSource
import com.circuitstitch.deferno.core.data.item.ItemSnapshot
import com.circuitstitch.deferno.core.data.item.ItemSync
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemSource
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
 * reconcile *algorithm*; this proves the *SQL path* — that [SqlDelightTaskLocalStore]'s row<->domain
 * mapping (including the #226 descendant counts), observe-via-Flow, and `db.transaction { }` atomicity
 * round-trip through a genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`, and that an
 * [ItemSync] `/items` reconcile commits the Task store through real SQLite end to end.
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
        descendantDone = 1,
        descendantTotal = 2,
        // Server-derived dependency flags must survive the real-SQLite round-trip (#290).
        blocked = true,
        isBlocker = true,
        // External provenance must survive the round-trip too; the full-equality assertion proves it.
        external = ExternalRef(ItemSource.GitHub, "octo/repo#a", "https://github.com/octo/repo/issues/1"),
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
    fun itemSyncReconcilesTheTaskStoreThroughRealSqlite() = runTest {
        val store = newStore()
        // Seed: a row that will survive (updated), and a row that will vanish (purged).
        store.upsert(summary("keep", title = "old"))
        store.upsert(summary("vanished"))

        // A Full /items snapshot: keep updated wholesale (descendant counts and all), fresh inserted,
        // a tombstone kept, and the locally-held "vanished" dropped entirely.
        val source = FakeItemSnapshotSource(
            ItemSnapshot(
                tasks = listOf(
                    full("keep").copy(title = "new"),
                    full("fresh"),
                    summary("tomb", deletedAt = Instant.parse("2026-06-01T00:00:00Z")),
                ),
            ),
        )
        val sync = ItemSync(
            store, FakeHabitLocalStore(), FakeChoreLocalStore(), FakeEventLocalStore(), source, FakePendingCreateStore(),
        )

        sync.refresh()

        // keep updated wholesale, fresh inserted, vanished purged.
        val keep = store.get(TaskId("keep"))
        assertEquals("new", keep?.title)
        assertEquals(HydrationState.Full, keep?.hydration)
        // the Full row's server-computed subtree counts round-trip through real SQLite (#226).
        assertEquals(1L, keep?.descendantDone)
        assertEquals(2L, keep?.descendantTotal)
        // and the server-derived dependency flags round-trip too (#290).
        assertTrue(keep?.blocked == true)
        assertTrue(keep?.isBlocker == true)
        assertEquals(WorkingState.Open, store.get(TaskId("fresh"))?.workingState)
        assertNull(store.get(TaskId("vanished")))
        // tombstone present + isDeleted, excluded from active.
        assertTrue(store.get(TaskId("tomb"))?.isDeleted == true)

        store.observeActive().test {
            val activeIds = awaitItem().map { it.id }.toSet()
            assertEquals(setOf(TaskId("keep"), TaskId("fresh")), activeIds)
            assertFalse(activeIds.contains(TaskId("tomb")))
            cancelAndIgnoreRemainingEvents()
        }
    }
}

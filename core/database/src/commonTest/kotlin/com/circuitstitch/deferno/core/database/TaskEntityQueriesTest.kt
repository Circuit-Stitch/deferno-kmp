package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.turbine.test
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.TaskEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the `taskEntity` schema + queries (issue #21) round-trip and observe correctly against the
 * in-memory driver — the ADR-0006 fast commonTest DB path. Exercises insert/upsert, the tombstone
 * filter, the children lookup, id listing (the reconcile's diff input, #22), and Flow observation.
 */
class TaskEntityQueriesTest {
    private fun DefernoDatabase.insert(
        id: String,
        title: String = "<title>",
        workingState: String = "Open",
        sequence: Long? = 1,
        parentId: String? = null,
        deletedAt: String? = null,
        hydration: String = "Summary",
        ownerOrgId: String? = null,
        descendantDone: Long? = null,
        descendantTotal: Long? = null,
    ) = taskEntityQueries.insertOrReplace(
        id = id,
        org_slug = "u-e4h2qk",
        owner_org_id = ownerOrgId,
        ref = "u-e4h2qk-$sequence",
        sequence = sequence,
        title = title,
        working_state = workingState,
        labels = "",
        parent_id = parentId,
        child_ids = "",
        complete_by = null,
        deadline_time_of_day = null,
        productive = null,
        desire = null,
        pinned = 0,
        date_created = "2026-05-20T16:11:42.625684725Z",
        finished_at = null,
        deleted_at = deletedAt,
        hydration_state = hydration,
        description = null,
        next_task_id = null,
        descendant_done = descendantDone,
        descendant_total = descendantTotal,
    )

    @Test
    fun insertAndSelectByIdRoundTrips() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a", title = "first", ownerOrgId = "org-1", hydration = "Full", descendantDone = 2, descendantTotal = 5)

        val row: TaskEntity? = db.taskEntityQueries.selectById("a").executeAsOneOrNull()
        assertEquals("a", row?.id)
        assertEquals("first", row?.title)
        assertEquals("org-1", row?.owner_org_id)
        assertEquals("Full", row?.hydration_state)
        assertEquals(0L, row?.pinned)
        // Server-computed subtree progress columns round-trip (#226, schema v7).
        assertEquals(2L, row?.descendant_done)
        assertEquals(5L, row?.descendant_total)
    }

    @Test
    fun insertOrReplaceUpsertsById() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a", title = "before")
        db.insert(id = "a", title = "after")

        assertEquals(1, db.taskEntityQueries.selectAll().executeAsList().size)
        assertEquals("after", db.taskEntityQueries.selectById("a").executeAsOneOrNull()?.title)
    }

    @Test
    fun selectAllActiveExcludesTombstonesAndOrdersBySequence() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a", sequence = 2)
        db.insert(id = "b", sequence = 1)
        db.insert(id = "gone", sequence = 3, deletedAt = "2026-06-01T00:00:00Z")

        val active = db.taskEntityQueries.selectAllActive().executeAsList()
        assertEquals(listOf("b", "a"), active.map { it.id })
        // The tombstone is still present in the full table (reconcile idempotence).
        assertEquals(3, db.taskEntityQueries.selectAll().executeAsList().size)
    }

    @Test
    fun selectChildrenReturnsLiveChildrenOfParent() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "parent")
        db.insert(id = "child1", parentId = "parent", sequence = 1)
        db.insert(id = "child2", parentId = "parent", sequence = 2)
        db.insert(id = "dead", parentId = "parent", sequence = 3, deletedAt = "2026-06-01T00:00:00Z")
        db.insert(id = "other", parentId = "elsewhere")

        val children = db.taskEntityQueries.selectChildren("parent").executeAsList()
        assertEquals(listOf("child1", "child2"), children.map { it.id })
    }

    @Test
    fun selectAllIdsListsEveryRow() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a")
        db.insert(id = "b", deletedAt = "2026-06-01T00:00:00Z")

        assertEquals(setOf("a", "b"), db.taskEntityQueries.selectAllIds().executeAsList().toSet())
    }

    @Test
    fun deleteByIdAndDeleteAllRemoveRows() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a")
        db.insert(id = "b")

        db.taskEntityQueries.deleteById("a")
        assertNull(db.taskEntityQueries.selectById("a").executeAsOneOrNull())
        assertEquals(1, db.taskEntityQueries.selectAll().executeAsList().size)

        db.taskEntityQueries.deleteAll()
        assertTrue(db.taskEntityQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun selectAllActiveEmitsOnInsert() = runTest {
        val db = inMemoryDefernoDatabase()
        db.taskEntityQueries.selectAllActive().asFlow().mapToList(Dispatchers.Default).test {
            assertTrue(awaitItem().isEmpty())
            db.insert(id = "a")
            assertEquals(listOf("a"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}

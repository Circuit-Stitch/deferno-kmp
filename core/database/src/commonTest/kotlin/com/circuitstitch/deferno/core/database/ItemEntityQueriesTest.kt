package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import app.cash.turbine.test
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.database.sql.ItemEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Proves the `itemEntity` schema + queries (#21, #422) round-trip and observe correctly against the
 * in-memory driver — the ADR-0006 fast commonTest DB path. Exercises insert/upsert, the tombstone
 * filter, the kind-narrowed and cross-kind children lookups, id listing (the reconcile's diff input),
 * the light-switch projection, and Flow observation.
 *
 * It replaces `TaskEntityQueriesTest`, which held this contract for one of four tables. The queries it
 * covers now answer for every kind, and two of them changed shape rather than merely widening:
 * `selectChildren` was always meant to be cross-kind and could only ever see Tasks, and
 * `selectDefinitionStates` replaces three kind-qualified queries over three tables.
 */
class ItemEntityQueriesTest {

    private fun DefernoDatabase.insert(
        id: String,
        kind: String = "Task",
        title: String = "<title>",
        workingState: String? = "Open",
        definitionState: String? = null,
        sequence: Long? = 1,
        parentId: String? = null,
        deletedAt: String? = null,
        hydration: String = "Summary",
        ownerOrgId: String? = null,
        descendantDone: Long? = null,
        descendantTotal: Long? = null,
        blocked: Long? = null,
        isBlocker: Long? = null,
        externalSource: String? = null,
        externalId: String? = null,
        externalUrl: String? = null,
        attachmentCount: Long? = null,
        attachmentTotalSize: Long? = null,
        blockedBy: String? = null,
        targetDate: String? = null,
        priority: String? = null,
        cadenceMode: String? = null,
    ) = itemEntityQueries.insertOrReplace(
        ItemEntity(
            id = id,
            kind = kind,
            org_slug = "u-e4h2qk",
            owner_org_id = ownerOrgId,
            ref = "u-e4h2qk-$sequence",
            sequence = sequence,
            title = title,
            parent_id = parentId,
            date_created = "2026-05-20T16:11:42.625684725Z",
            deleted_at = deletedAt,
            hydration_state = hydration,
            description = null,
            labels = "",
            complete_by = null,
            target_date = targetDate,
            priority = priority,
            pinned = 0,
            blocked = blocked,
            is_blocker = isBlocker,
            deadline_time_of_day = null,
            working_state = workingState,
            finished_at = null,
            child_ids = "",
            descendant_done = descendantDone,
            descendant_total = descendantTotal,
            blocked_by = blockedBy,
            next_task_id = null,
            productive = null,
            desire = null,
            external_source = externalSource,
            external_id = externalId,
            external_url = externalUrl,
            attachment_count = attachmentCount,
            attachment_total_size = attachmentTotalSize,
            definition_state = definitionState,
            series_id = null,
            recurrence_type = null,
            recurrence_days = "",
            recurrence_interval = null,
            recurrence_anchor_type = null,
            recurrence_anchor_day = null,
            recurrence_anchor_nth = null,
            recurrence_anchor_weekday = null,
            recurrence_month = null,
            recurrence_day = null,
            recurrence_rrule = null,
            recurrence_end_type = null,
            recurrence_end_date = null,
            recurrence_end_count = null,
            recurrence_raw_type = null,
            cadence_mode = cadenceMode,
            all_day = 0,
            end_time = null,
            start_time_of_day = null,
            end_time_of_day = null,
        ),
    )

    /** A recurring row: the light switch instead of a working state, and no Task-only columns. */
    private fun DefernoDatabase.insertHabit(
        id: String,
        sequence: Long? = 1,
        definitionState: String = "Active",
        deletedAt: String? = null,
        parentId: String? = null,
    ) = insert(
        id = id,
        kind = "Habit",
        title = "habit-$id",
        workingState = null,
        definitionState = definitionState,
        sequence = sequence,
        parentId = parentId,
        deletedAt = deletedAt,
    )

    @Test
    fun insertAndSelectByIdRoundTrips() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a", title = "first", ownerOrgId = "org-1", hydration = "Full", descendantDone = 2, descendantTotal = 5, blocked = 1, isBlocker = 1, attachmentCount = 3, attachmentTotalSize = 4096)

        val row: ItemEntity? = db.itemEntityQueries.selectById("a").executeAsOneOrNull()
        assertEquals("a", row?.id)
        assertEquals("Task", row?.kind)
        assertEquals("first", row?.title)
        assertEquals("org-1", row?.owner_org_id)
        assertEquals("Full", row?.hydration_state)
        assertEquals(0L, row?.pinned)
        // Server-computed subtree progress columns round-trip (#226, schema v7).
        assertEquals(2L, row?.descendant_done)
        assertEquals(5L, row?.descendant_total)
        // Server-derived dependency flags round-trip (#290, schema v10).
        assertEquals(1L, row?.blocked)
        assertEquals(1L, row?.is_blocker)
        // Attachment rollup columns round-trip (#311, schema v12).
        assertEquals(3L, row?.attachment_count)
        assertEquals(4096L, row?.attachment_total_size)
    }

    /**
     * The whole-row `VALUES ?` bind is positional, so a column declared out of order — or a caller that
     * filled the wrong one — writes into its neighbour silently. Across one wide table that is a
     * cross-kind failure, where four narrow tables kept each kind's columns apart structurally.
     */
    @Test
    fun aRecurringRowKeepsItsOwnColumnsAndLeavesTheTaskOnlyOnesNull() {
        val db = inMemoryDefernoDatabase()
        db.insertHabit(id = "h", definitionState = "Archived")

        val row = db.itemEntityQueries.selectById("h").executeAsOneOrNull()
        assertEquals("Habit", row?.kind)
        assertEquals("Archived", row?.definition_state)
        assertNull(row?.working_state)
        assertNull(row?.descendant_total)
        assertNull(row?.attachment_count)
        assertNull(row?.cadence_mode)
    }

    @Test
    fun insertOrReplaceUpsertsById() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a", title = "before")
        db.insert(id = "a", title = "after")

        assertEquals(1, db.itemEntityQueries.selectAll().executeAsList().size)
        assertEquals("after", db.itemEntityQueries.selectById("a").executeAsOneOrNull()?.title)
    }

    /**
     * One `sequence` order across every kind. `sequence` is unique per org across kinds — it is what the
     * `{org}-{sequence}` ref is built from — so this is the coherent order the four concatenated per-kind
     * orders were an artifact of.
     */
    @Test
    fun selectAllActiveExcludesTombstonesAndOrdersBySequenceAcrossKinds() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a", sequence = 2)
        db.insertHabit(id = "h", sequence = 1)
        db.insert(id = "gone", sequence = 3, deletedAt = "2026-06-01T00:00:00Z")

        val active = db.itemEntityQueries.selectAllActive().executeAsList()
        assertEquals(listOf("h", "a"), active.map { it.id })
        // The tombstone is still present in the full table (reconcile idempotence).
        assertEquals(3, db.itemEntityQueries.selectAll().executeAsList().size)
    }

    @Test
    fun selectActiveOfKindNarrowsToOneKindInSql() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "t1", sequence = 1)
        db.insertHabit(id = "h1", sequence = 2)
        db.insert(id = "t2", sequence = 3)
        db.insert(id = "t-gone", sequence = 4, deletedAt = "2026-06-01T00:00:00Z")

        assertEquals(listOf("t1", "t2"), db.itemEntityQueries.selectActiveOfKind("Task").executeAsList().map { it.id })
        assertEquals(listOf("h1"), db.itemEntityQueries.selectActiveOfKind("Habit").executeAsList().map { it.id })
        // A kind nothing was written under is empty rather than an error — the column is free text in SQL.
        assertTrue(db.itemEntityQueries.selectActiveOfKind("Chore").executeAsList().isEmpty())
    }

    /**
     * The forest nests a child of any kind under a parent of any kind, so this read was always meant to
     * be cross-kind — and could only ever see Tasks while the tables were split.
     */
    @Test
    fun selectChildrenReturnsLiveChildrenOfAnyKind() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "parent")
        db.insert(id = "child1", parentId = "parent", sequence = 1)
        db.insertHabit(id = "child2", parentId = "parent", sequence = 2)
        db.insert(id = "dead", parentId = "parent", sequence = 3, deletedAt = "2026-06-01T00:00:00Z")
        db.insert(id = "other", parentId = "elsewhere")

        val children = db.itemEntityQueries.selectChildren("parent").executeAsList()
        assertEquals(listOf("child1", "child2"), children.map { it.id })
    }

    @Test
    fun selectAllIdsListsEveryRow() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a")
        db.insert(id = "b", deletedAt = "2026-06-01T00:00:00Z")

        assertEquals(setOf("a", "b"), db.itemEntityQueries.selectAllIds().executeAsList().toSet())
    }

    /**
     * The light switch alone, for the occurrence-state reading (ADR-0053 decision 4). A Task is excluded
     * by its NULL `definition_state` and a tombstone by the `deleted_at` clause — both would otherwise
     * license a derived Missed for a firing whose definition is switched off or gone.
     */
    @Test
    fun selectDefinitionStatesReturnsOnlyLiveRecurringRowsWithTheirKind() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "t1") // a Task has no light switch
        db.insertHabit(id = "h1")
        db.insertHabit(id = "h-gone", deletedAt = "2026-06-01T00:00:00Z")

        val states = db.itemEntityQueries.selectDefinitionStates().executeAsList()
        assertEquals(listOf("h1"), states.map { it.id })
        assertEquals("Habit", states.single().kind)
        assertEquals("Active", states.single().definition_state)

        assertEquals("Active", db.itemEntityQueries.selectDefinitionStateById("h1").executeAsOneOrNull())
        assertNull(db.itemEntityQueries.selectDefinitionStateById("t1").executeAsOneOrNull())
        assertNull(db.itemEntityQueries.selectDefinitionStateById("h-gone").executeAsOneOrNull())
    }

    @Test
    fun deleteByIdAndDeleteAllRemoveRows() {
        val db = inMemoryDefernoDatabase()
        db.insert(id = "a")
        db.insertHabit(id = "b")

        db.itemEntityQueries.deleteById("a")
        assertNull(db.itemEntityQueries.selectById("a").executeAsOneOrNull())
        assertEquals(1, db.itemEntityQueries.selectAll().executeAsList().size)

        db.itemEntityQueries.deleteAll()
        assertTrue(db.itemEntityQueries.selectAll().executeAsList().isEmpty())
    }

    @Test
    fun selectAllActiveEmitsOnInsert() = runTest {
        val db = inMemoryDefernoDatabase()
        db.itemEntityQueries.selectAllActive().asFlow().mapToList(Dispatchers.Default).test {
            assertTrue(awaitItem().isEmpty())
            db.insert(id = "a")
            assertEquals(listOf("a"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A write of any kind re-runs this query, because SQLDelight notifies per **table** and one table
     * now holds every kind. The narrowing is the `WHERE` clause's job, not the notification's — so a
     * Habit write costs the Task list an extra emission carrying the same rows, and must never put the
     * Habit in the answer.
     */
    @Test
    fun selectActiveOfKindReEmitsOnAnyWriteButAnswersOnlyForItsOwnKind() = runTest {
        val db = inMemoryDefernoDatabase()
        db.itemEntityQueries.selectActiveOfKind("Task").asFlow().mapToList(Dispatchers.Default).test {
            assertTrue(awaitItem().isEmpty())

            db.insertHabit(id = "h")
            assertTrue(awaitItem().isEmpty(), "a Habit write re-runs the query but must not enter the answer")

            db.insert(id = "t")
            assertEquals(listOf("t"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }
}

package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.database.sql.TaskEntity
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Proves the row<->domain conversion (#22) is a faithful, total round-trip — the single place the
 * SQL primitives (TEXT/INTEGER/REAL) are translated into the domain `Task`'s rich types (enums,
 * `Instant`s, the `\n`-joined list columns, the `Long`-encoded `Boolean`). core:database keeps the
 * table adapter-free on purpose (ADR-0011) and pushes this conversion up here, so it is the load-
 * bearing seam the whole reconcile path rides on; a lossy field would silently corrupt the cache.
 *
 * Covers the listed cases: a summary row, a full (hydrated) row, a tombstoned row, and empty +
 * multi-element labels/children — plus the defensive decode of an unrecognised enum token.
 */
class TaskEntityMappingTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    @Test
    fun summaryTaskRoundTrips() {
        val task = Task(
            id = TaskId("t-1"),
            orgSlug = "u-e4h2qk",
            title = "Summary task",
            workingState = WorkingState.Open,
            labels = emptyList(),
            children = emptyList(),
            pinned = false,
            sequence = 7,
            ref = "u-e4h2qk-7",
            dateCreated = created,
            hydration = HydrationState.Summary,
        )

        assertEquals(task, task.toEntity().toDomain())
    }

    @Test
    fun deadlineTimeOfDayRoundTripsThroughTheRow() {
        // #348: the deadline clock must survive the DB round-trip (it's the all-day-vs-timed signal).
        val timed = Task(
            id = TaskId("t-tod"),
            orgSlug = "u-e4h2qk",
            title = "Timed deadline",
            workingState = WorkingState.Open,
            completeBy = Instant.parse("2026-06-20T12:00:00Z"),
            deadlineTimeOfDay = LocalTime(14, 30),
            dateCreated = created,
            hydration = HydrationState.Summary,
        )
        assertEquals(timed, timed.toEntity().toDomain())
        // Absent (all-day) survives as null.
        assertEquals(null, timed.copy(deadlineTimeOfDay = null).toEntity().toDomain().deadlineTimeOfDay)
    }

    @Test
    fun fullTaskRoundTripsIncludingEnrichmentFields() {
        val task = Task(
            id = TaskId("t-2"),
            orgSlug = "u-e4h2qk",
            title = "Full task",
            workingState = WorkingState.InProgress,
            labels = listOf("home", "urgent"),
            parentId = TaskId("p-1"),
            children = listOf(TaskId("c-1"), TaskId("c-2")),
            completeBy = Instant.parse("2026-06-01T09:00:00Z"),
            productive = 0.8,
            desire = 0.3,
            pinned = true,
            sequence = 12,
            ref = "u-e4h2qk-12",
            dateCreated = created,
            finishedAt = Instant.parse("2026-06-02T10:00:00Z"),
            hydration = HydrationState.Full,
            ownerOrgId = OrgId("org-9"),
            description = "the long body",
            nextTaskId = TaskId("n-1"),
            // Server-computed subtree progress from the /items snapshot (#226).
            descendantDone = 3,
            descendantTotal = 8,
            // Server-derived dependency flags from the /items snapshot (#290).
            blocked = true,
            isBlocker = true,
        )

        assertEquals(task, task.toEntity().toDomain())
    }

    @Test
    fun blockedFlagsRoundTripAndNullColumnDecodesToFalse() {
        // #290: true round-trips through the row...
        val blocked = sampleTask(pinned = false).copy(blocked = true, isBlocker = true)
        assertEquals(blocked, blocked.toEntity().toDomain())
        assertEquals(1L, blocked.toEntity().blocked)
        assertEquals(1L, blocked.toEntity().is_blocker)
        // ...and a pre-migration NULL column (sampleEntity leaves them null) decodes to false, not a crash.
        val decoded = sampleEntity().toDomain()
        assertEquals(false, decoded.blocked)
        assertEquals(false, decoded.isBlocker)
    }

    @Test
    fun externalProvenanceRoundTripsAndDecodesDefensively() {
        // A GitHub provenance round-trips through the three columns (the enum name + id + url)...
        val gh = sampleTask(pinned = false).copy(external = ExternalRef(ItemSource.GitHub, "octo/repo#7", "https://gh/7"))
        assertEquals(gh, gh.toEntity().toDomain())
        assertEquals("GitHub", gh.toEntity().external_source)
        assertEquals("octo/repo#7", gh.toEntity().external_id)
        // ...a native row (no provenance columns) decodes to null...
        assertNull(sampleEntity().toDomain().external)
        // ...an unrecognised source token degrades to null, not a crash (forward-additive safe)...
        assertNull(sampleEntity(externalSource = "bitbucket", externalId = "x#1").toDomain().external)
        // ...and a source with no id is malformed → read as native.
        assertNull(sampleEntity(externalSource = "GitHub", externalId = null).toDomain().external)
    }

    @Test
    fun tombstonedTaskRoundTripsAndIsDeleted() {
        val task = Task(
            id = TaskId("t-3"),
            orgSlug = "u-e4h2qk",
            title = "Deleted",
            workingState = WorkingState.Done,
            dateCreated = created,
            deletedAt = Instant.parse("2026-06-03T00:00:00Z"),
            hydration = HydrationState.Summary,
        )

        val roundTripped = task.toEntity().toDomain()
        assertEquals(task, roundTripped)
        assertEquals(true, roundTripped.isDeleted)
    }

    @Test
    fun emptyLabelsAndChildrenDecodeToEmptyLists() {
        val entity = sampleEntity(labels = "", childIds = "")

        val task = entity.toDomain()
        assertEquals(emptyList(), task.labels)
        assertEquals(emptyList(), task.children)
    }

    @Test
    fun multiLabelsAndChildrenJoinAndSplitOnNewline() {
        val task = Task(
            id = TaskId("t-4"),
            orgSlug = "u",
            title = "x",
            workingState = WorkingState.Open,
            labels = listOf("a", "b", "c"),
            children = listOf(TaskId("c1"), TaskId("c2")),
            dateCreated = created,
        )

        val entity = task.toEntity()
        assertEquals("a\nb\nc", entity.labels)
        assertEquals("c1\nc2", entity.child_ids)
        assertEquals(task.labels, entity.toDomain().labels)
        assertEquals(task.children, entity.toDomain().children)
    }

    @Test
    fun unrecognisedWorkingStateDecodesToOpenInsteadOfThrowing() {
        val entity = sampleEntity(workingState = "Frobnicated")

        assertEquals(WorkingState.Open, entity.toDomain().workingState)
    }

    @Test
    fun unrecognisedHydrationStateDecodesToSummaryInsteadOfThrowing() {
        val entity = sampleEntity(hydrationState = "Partial")

        assertEquals(HydrationState.Summary, entity.toDomain().hydration)
    }

    @Test
    fun pinnedEncodesToOneAndZero() {
        assertEquals(1L, sampleTask(pinned = true).toEntity().pinned)
        assertEquals(0L, sampleTask(pinned = false).toEntity().pinned)
    }

    private fun sampleTask(pinned: Boolean) = Task(
        id = TaskId("t"),
        orgSlug = "u",
        title = "x",
        workingState = WorkingState.Open,
        pinned = pinned,
        dateCreated = created,
    )

    private fun sampleEntity(
        labels: String = "",
        childIds: String = "",
        workingState: String = "Open",
        hydrationState: String = "Summary",
        externalSource: String? = null,
        externalId: String? = null,
        externalUrl: String? = null,
    ) = TaskEntity(
        id = "t",
        org_slug = "u",
        owner_org_id = null,
        ref = null,
        sequence = null,
        title = "x",
        working_state = workingState,
        labels = labels,
        parent_id = null,
        child_ids = childIds,
        complete_by = null,
        productive = null,
        desire = null,
        pinned = 0,
        date_created = created.toString(),
        finished_at = null,
        deleted_at = null,
        hydration_state = hydrationState,
        description = null,
        next_task_id = null,
        deadline_time_of_day = null,
        descendant_done = null,
        descendant_total = null,
        blocked = null,
        is_blocker = null,
        external_source = externalSource,
        external_id = externalId,
        external_url = externalUrl,
    )
}

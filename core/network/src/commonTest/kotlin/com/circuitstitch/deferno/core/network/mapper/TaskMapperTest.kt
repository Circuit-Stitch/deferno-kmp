package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.dto.TaskStatusWire
import com.circuitstitch.deferno.core.network.dto.TaskSummaryDto
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The DTO→domain `Task` mapping (ADR-0011 "condense at the edge", #18). A summary maps to a
 * [HydrationState.Summary] Task with the full-only enrichment left null; a detail maps to a
 * [HydrationState.Full] Task carrying owner/description/next. Timestamps parse RFC3339; ids become
 * value classes. The [ItemView] task-or-null helper extracts a domain Task only from the task variant.
 */
class TaskMapperTest {

    private fun summary(
        id: String = "7033cae7-eff6-4df1-bed9-01d16e89c2b0",
        status: TaskStatusWire = TaskStatusWire.Dropped,
        ref: String? = "u-e4h2qk-1",
        sequence: Long? = 1L,
        parentId: String? = null,
        deletedAt: String? = null,
    ) = TaskSummaryDto(
        id = id,
        title = "<title>",
        status = status,
        labels = listOf("family"),
        parentId = parentId,
        children = listOf("461dfe4c-90db-4220-a93b-c645ed075688"),
        completeBy = "2026-04-10T07:45:00Z",
        productive = -0.45,
        desire = null,
        dateCreated = "2026-04-10T17:47:32.694061553Z",
        pinned = true,
        ref = ref,
        orgSlug = "u-e4h2qk",
        sequence = sequence,
        type = "task",
        deletedAt = deletedAt,
    )

    @Test
    fun taskSummaryDtoMapsToSummaryHydratedTask() {
        val task = summary().toDomain()

        assertEquals(TaskId("7033cae7-eff6-4df1-bed9-01d16e89c2b0"), task.id)
        assertEquals("u-e4h2qk", task.orgSlug)
        assertEquals("<title>", task.title)
        assertEquals(WorkingState.Dropped, task.workingState)
        assertEquals(listOf("family"), task.labels)
        assertEquals(listOf(TaskId("461dfe4c-90db-4220-a93b-c645ed075688")), task.children)
        assertEquals(Instant.parse("2026-04-10T07:45:00Z"), task.completeBy)
        assertEquals(-0.45, task.productive)
        assertNull(task.desire)
        assertEquals(true, task.pinned)
        assertEquals(1L, task.sequence)
        assertEquals("u-e4h2qk-1", task.ref)
        assertEquals(Instant.parse("2026-04-10T17:47:32.694061553Z"), task.dateCreated)
        assertEquals(HydrationState.Summary, task.hydration)
        // Full-only enrichment is absent on a summary.
        assertNull(task.ownerOrgId)
        assertNull(task.description)
        assertNull(task.nextTaskId)
        assertNull(task.parentId)
    }

    @Test
    fun taskSummaryDtoMapsParentIdAndNullRef() {
        val task = summary(ref = null, sequence = null, parentId = "bffc4836-5899-4c47-abcc-90181bff5d68").toDomain()
        assertNull(task.ref)
        assertNull(task.sequence)
        assertEquals(TaskId("bffc4836-5899-4c47-abcc-90181bff5d68"), task.parentId)
    }

    @Test
    fun taskSummaryDtoMapsTombstone() {
        val task = summary(deletedAt = "2026-06-01T00:00:00Z").toDomain()
        assertEquals(Instant.parse("2026-06-01T00:00:00Z"), task.deletedAt)
        assertEquals(true, task.isDeleted)
    }

    private fun detail(
        finishedAt: String? = null,
        deletedAt: String? = null,
    ) = TaskDetailDto(
        id = "948bcfab-063d-4499-b2de-f21801bc6f9c",
        orgSlug = "u-e4h2qk",
        ownerOrgId = "ebca93e5-d663-4624-9fe9-c5361b5b4390",
        ref = "u-e4h2qk-311",
        sequence = 311L,
        title = "<title>",
        status = TaskStatusWire.Open,
        labels = emptyList(),
        parentId = null,
        children = listOf("4c9215e2-111b-4727-af2f-f4b9456cb0ff"),
        completeBy = null,
        productive = null,
        desire = null,
        pinned = true,
        dateCreated = "2026-05-20T16:11:42.625684725Z",
        finishedAt = finishedAt,
        deletedAt = deletedAt,
        description = "<description>",
        nextTaskId = null,
        type = "task",
    )

    @Test
    fun taskDetailDtoMapsToFullyHydratedTask() {
        val task = detail().toDomain()

        assertEquals(TaskId("948bcfab-063d-4499-b2de-f21801bc6f9c"), task.id)
        assertEquals(HydrationState.Full, task.hydration)
        assertEquals(OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"), task.ownerOrgId)
        assertEquals("<description>", task.description)
        assertNull(task.nextTaskId)
        assertEquals(listOf(TaskId("4c9215e2-111b-4727-af2f-f4b9456cb0ff")), task.children)
        assertEquals(WorkingState.Open, task.workingState)
        assertEquals(true, task.pinned)
    }

    @Test
    fun taskDetailDtoMapsFinishedAndTombstone() {
        val task = detail(finishedAt = "2026-06-02T10:00:00Z", deletedAt = "2026-06-03T10:00:00Z").toDomain()
        assertEquals(Instant.parse("2026-06-02T10:00:00Z"), task.finishedAt)
        assertEquals(Instant.parse("2026-06-03T10:00:00Z"), task.deletedAt)
    }

    @Test
    fun itemViewTaskMapsToDomainTaskOthersToNull() {
        val taskView = ItemView.Task(
            id = "948bcfab-063d-4499-b2de-f21801bc6f9c",
            orgSlug = "u-e4h2qk",
            ownerOrgId = "ebca93e5-d663-4624-9fe9-c5361b5b4390",
            ref = "u-e4h2qk-311",
            sequence = 311L,
            title = "<title>",
            status = TaskStatusWire.Open,
            labels = emptyList(),
            parentId = null,
            children = emptyList(),
            completeBy = null,
            productive = null,
            desire = null,
            pinned = true,
            dateCreated = "2026-05-20T16:11:42.625684725Z",
            finishedAt = null,
            deletedAt = null,
            description = "<description>",
            nextTaskId = null,
        )
        val mapped = taskView.asTaskOrNull()
        assertEquals(TaskId("948bcfab-063d-4499-b2de-f21801bc6f9c"), mapped?.id)
        assertEquals(HydrationState.Full, mapped?.hydration)

        val habitView = ItemView.Habit(
            id = "77dd6a6e-b936-4f61-9807-c3a6b647f9f1",
            orgSlug = "u-e4h2qk",
            ownerOrgId = "ebca93e5-d663-4624-9fe9-c5361b5b4390",
            ref = "u-e4h2qk-185",
            sequence = 185L,
            title = "<title>",
            status = com.circuitstitch.deferno.core.network.dto.DefStatusWire.Active,
            labels = emptyList(),
            dateCreated = "2026-05-04T01:53:05.597388900Z",
            seriesId = "b7c21959-c5f6-4087-8ab2-7690c81e463a",
            subtaskTemplate = emptyList(),
        )
        assertNull(habitView.asTaskOrNull())
    }
}

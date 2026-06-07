package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The intent → endpoint → minimal-body table (ADR-0001, ADR-0011, #23). Pins the EXACT wire request
 * each [Mutation] emits — proving the load-bearing rules: a set emits its value, a "clear" emits an
 * explicit `null` (distinct from omit), the omit-only `status` is only ever set, and **no intent ever
 * serialises an absent field** (every body lists only the keys it changes). Also proves the optimistic
 * [TaskMutation.applyTo]/[PlanMutation.applyTo] transforms are correct and idempotent (replay-safe).
 */
class MutationTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")
    private val date = LocalDate(2026, 6, 7)
    private val tz = "America/Los_Angeles"

    private fun task(
        id: String = "a",
        title: String = "title-$id",
        state: WorkingState = WorkingState.Open,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-test",
        title = title,
        workingState = state,
        dateCreated = created,
        hydration = HydrationState.Summary,
    )

    // --- the intent → endpoint → minimal-body table ---

    @Test
    fun setWorkingStateEmitsStatusToken() {
        val request = SetWorkingState(TaskId("a"), WorkingState.Done).toRequest()
        assertEquals(OutboxMethod.Patch, request.method)
        assertEquals(listOf("tasks", "a"), request.path)
        assertEquals("""{"status":"done"}""", request.body)
    }

    @Test
    fun setWorkingStateUsesHyphenatedWireTokens() {
        assertEquals("""{"status":"in-progress"}""", SetWorkingState(TaskId("a"), WorkingState.InProgress).toRequest().body)
        assertEquals("""{"status":"in-review"}""", SetWorkingState(TaskId("a"), WorkingState.InReview).toRequest().body)
    }

    @Test
    fun renameEmitsOnlyTitle() {
        assertEquals("""{"title":"New title"}""", Rename(TaskId("a"), "New title").toRequest().body)
    }

    @Test
    fun setDeadlineEmitsRfc3339CompleteBy() {
        val due = Instant.parse("2026-06-07T09:00:00Z")
        assertEquals("""{"complete_by":"2026-06-07T09:00:00Z"}""", SetDeadline(TaskId("a"), due).toRequest().body)
    }

    @Test
    fun clearDeadlineEmitsExplicitNull() {
        // null = "clear it" (ADR-0011), NOT an omitted field.
        assertEquals("""{"complete_by":null}""", ClearDeadline(TaskId("a")).toRequest().body)
    }

    @Test
    fun setDescriptionEmitsOnlyDescription() {
        assertEquals("""{"description":"the body"}""", SetDescription(TaskId("a"), "the body").toRequest().body)
    }

    @Test
    fun clearDescriptionEmitsExplicitNull() {
        assertEquals("""{"description":null}""", ClearDescription(TaskId("a")).toRequest().body)
    }

    @Test
    fun setLabelsEmitsArray() {
        assertEquals("""{"labels":["home","urgent"]}""", SetLabels(TaskId("a"), listOf("home", "urgent")).toRequest().body)
        // An empty list is the explicit-empty value, still present (never absent).
        assertEquals("""{"labels":[]}""", SetLabels(TaskId("a"), emptyList()).toRequest().body)
    }

    @Test
    fun setPinnedEmitsBoolean() {
        assertEquals("""{"pinned":true}""", SetPinned(TaskId("a"), true).toRequest().body)
        assertEquals("""{"pinned":false}""", SetPinned(TaskId("a"), false).toRequest().body)
    }

    @Test
    fun deleteTaskIsBodilessDelete() {
        val request = DeleteTask(TaskId("a"), created).toRequest()
        assertEquals(OutboxMethod.Delete, request.method)
        assertEquals(listOf("tasks", "a"), request.path)
        assertEquals(null, request.body)
    }

    @Test
    fun planAddEmitsTaskIdDateTz() {
        val request = PlanAdd(TaskId("t1"), date, tz).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("tasks", "plan", "add"), request.path)
        assertEquals("""{"task_id":"t1","date":"2026-06-07","tz":"America/Los_Angeles"}""", request.body)
    }

    @Test
    fun planRemoveEmitsTaskIdDateTz() {
        val request = PlanRemove(TaskId("t1"), date, tz).toRequest()
        assertEquals(listOf("tasks", "plan", "remove"), request.path)
        assertEquals("""{"task_id":"t1","date":"2026-06-07","tz":"America/Los_Angeles"}""", request.body)
    }

    @Test
    fun planReorderEmitsOrderedTaskIds() {
        val request = PlanReorder(listOf(TaskId("t1"), TaskId("t2")), date, tz).toRequest()
        assertEquals(listOf("tasks", "plan", "reorder"), request.path)
        assertEquals("""{"task_ids":["t1","t2"],"date":"2026-06-07","tz":"America/Los_Angeles"}""", request.body)
    }

    @Test
    fun targetsPartitionByEntity() {
        assertEquals("task:a", SetWorkingState(TaskId("a"), WorkingState.Done).target)
        assertEquals("task:a", DeleteTask(TaskId("a"), created).target)
        assertEquals("plan:2026-06-07:America/Los_Angeles", PlanAdd(TaskId("t1"), date, tz).target)
    }

    // --- optimistic apply: correctness + idempotence (replay-safety) ---

    @Test
    fun taskApplyTransformsTheRightField() {
        val base = task(state = WorkingState.Open, title = "old")
        assertEquals(WorkingState.Done, SetWorkingState(TaskId("a"), WorkingState.Done).applyTo(base).workingState)
        assertEquals("new", Rename(TaskId("a"), "new").applyTo(base).title)
        assertEquals(null, ClearDeadline(TaskId("a")).applyTo(base.copy(completeBy = created)).completeBy)
        assertEquals(null, ClearDescription(TaskId("a")).applyTo(base.copy(description = "x")).description)
        assertEquals(listOf("home"), SetLabels(TaskId("a"), listOf("home")).applyTo(base).labels)
        assertTrue(SetPinned(TaskId("a"), true).applyTo(base).pinned)
        assertTrue(DeleteTask(TaskId("a"), created).applyTo(base).isDeleted)
    }

    @Test
    fun taskApplyIsIdempotent() {
        val base = task(state = WorkingState.Open)
        val intents = listOf(
            SetWorkingState(TaskId("a"), WorkingState.Done),
            Rename(TaskId("a"), "x"),
            SetDeadline(TaskId("a"), created),
            ClearDeadline(TaskId("a")),
            SetLabels(TaskId("a"), listOf("l")),
            SetPinned(TaskId("a"), true),
            DeleteTask(TaskId("a"), created),
        )
        for (intent in intents) {
            val once = intent.applyTo(base)
            assertEquals(once, intent.applyTo(once), "applyTo must be idempotent for $intent")
        }
    }

    @Test
    fun planApplyTransformsAndIsIdempotent() {
        val t1 = TaskId("t1")
        val t2 = TaskId("t2")

        val added = PlanAdd(t1, date, tz)
        assertEquals(listOf(t1), added.applyTo(emptyList()))
        assertEquals(listOf(t1), added.applyTo(listOf(t1)), "add is a no-op when already present")

        val removed = PlanRemove(t1, date, tz)
        assertEquals(listOf(t2), removed.applyTo(listOf(t1, t2)))
        assertEquals(listOf(t2), removed.applyTo(listOf(t2)), "remove is a no-op when already absent")

        val reordered = PlanReorder(listOf(t2, t1), date, tz)
        assertEquals(listOf(t2, t1), reordered.applyTo(listOf(t1, t2)))
        assertEquals(listOf(t2, t1), reordered.applyTo(listOf(t2, t1)), "reorder replays to the same order")
    }
}

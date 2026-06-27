package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
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

    private fun habit(id: String = "x", state: DefinitionState = DefinitionState.Active) = Habit(
        id = HabitId(id), orgSlug = "u-test", title = "habit-$id", definitionState = state, dateCreated = created,
    )

    private fun chore(id: String = "x", state: DefinitionState = DefinitionState.Active) = Chore(
        id = ChoreId(id), orgSlug = "u-test", title = "chore-$id", definitionState = state, dateCreated = created,
    )

    private fun event(id: String = "x", state: DefinitionState = DefinitionState.Active) = Event(
        id = EventId(id), orgSlug = "u-test", title = "event-$id", definitionState = state, dateCreated = created,
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
    fun moveEmitsNewParentAndPosition() {
        val request = Move(id = "x", newParentId = "p", position = 2).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("items", "x", "move"), request.path)
        assertEquals("""{"new_parent_id":"p","position":2}""", request.body)
    }

    @Test
    fun moveToRootEmitsExplicitNullParent() {
        // null parent = "detach to root" (ADR-0034), an explicit wire null distinct from omit (ADR-0011).
        assertEquals("""{"new_parent_id":null,"position":0}""", Move(id = "x", newParentId = null, position = 0).toRequest().body)
    }

    // --- SetDefinitionState (#299): the recurring "light switch" — per-kind PATCH + per-kind apply ---

    @Test
    fun setDefinitionStateEmitsAStatusPatchPerKind() {
        // Best-guess endpoint (#299): PATCH {habits|chores|events}/{id} with the wire status token.
        val habit = SetDefinitionState("h1", ItemKind.Habit, DefinitionState.Archived).toRequest()
        assertEquals(OutboxMethod.Patch, habit.method)
        assertEquals(listOf("habits", "h1"), habit.path)
        assertEquals("""{"status":"archived"}""", habit.body)

        val chore = SetDefinitionState("c1", ItemKind.Chore, DefinitionState.Active).toRequest()
        assertEquals(listOf("chores", "c1"), chore.path)
        assertEquals("""{"status":"active"}""", chore.body)

        val event = SetDefinitionState("e1", ItemKind.Event, DefinitionState.InReview).toRequest()
        assertEquals(listOf("events", "e1"), event.path)
        assertEquals("""{"status":"in-review"}""", event.body)
    }

    @Test
    fun setDefinitionStateTargetsTheRawItemId() {
        assertEquals("item:h1", SetDefinitionState("h1", ItemKind.Habit, DefinitionState.Archived).target)
    }

    @Test
    fun setDefinitionStateAppliesPerKindAndIsIdempotent() {
        val intent = SetDefinitionState("x", ItemKind.Habit, DefinitionState.Archived)

        val h = habit(state = DefinitionState.Active)
        assertEquals(DefinitionState.Archived, intent.applyTo(h).definitionState)
        assertEquals(intent.applyTo(h), intent.applyTo(intent.applyTo(h)), "habit applyTo must be idempotent")

        val c = chore(state = DefinitionState.Active)
        assertEquals(DefinitionState.Archived, intent.applyTo(c).definitionState)
        assertEquals(intent.applyTo(c), intent.applyTo(intent.applyTo(c)), "chore applyTo must be idempotent")

        val e = event(state = DefinitionState.Active)
        assertEquals(DefinitionState.Archived, intent.applyTo(e).definitionState)
        assertEquals(intent.applyTo(e), intent.applyTo(intent.applyTo(e)), "event applyTo must be idempotent")
    }

    @Test
    fun targetsPartitionByEntity() {
        assertEquals("task:a", SetWorkingState(TaskId("a"), WorkingState.Done).target)
        assertEquals("task:a", DeleteTask(TaskId("a"), created).target)
        assertEquals("plan:2026-06-07:America/Los_Angeles", PlanAdd(TaskId("t1"), date, tz).target)
        assertEquals("item:x", Move(id = "x", newParentId = null, position = 0).target)
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

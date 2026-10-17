package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.model.plugin.Progress
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The ordered Task refs a plan fixture wants — a plan write is kind-neutral since #385. */
private fun taskRef(id: String) = PlanItemRef(id, ItemKind.Task)

/**
 * The intent → endpoint → minimal-body table (ADR-0001, ADR-0011, #23). Pins the EXACT wire request
 * each [Mutation] emits — proving the load-bearing rules: a set emits its value, a "clear" emits an
 * explicit `null` (distinct from omit), the omit-only `status` is only ever set, and **no intent ever
 * serialises an absent field** (every body lists only the keys it changes). Also proves the optimistic
 * [TaskMutation.applyTo]/[PlanMutation.applyTo] transforms are correct and idempotent (replay-safe).
 *
 * Each route family also pins its [OutboxRequest.acceptsActivityStamp] declaration (#364) right beside
 * the endpoint it belongs to, so the two can't drift apart: a `true` on a route with a strict payload
 * 422s — Terminal — and dead-letters the user's write, which is far worse than the missing audit row a
 * wrong `false` costs.
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

    /** A cached Task as the store now holds it — the record every [TaskMutation.applyTo] transforms. */
    private fun taskItem(
        id: String = "a",
        title: String = "title-$id",
        state: WorkingState = WorkingState.Open,
    ) = ParityRecipe.read(task(id, title, state))

    /** A cached recurring definition, for the one intent that addresses a raw item id of any kind. */
    private fun definitionItem(id: String = "x", state: DefinitionState = DefinitionState.Active) =
        ParityRecipe.read(
            Habit(
                id = HabitId(id),
                orgSlug = "u-test",
                title = "habit-$id",
                definitionState = state,
                dateCreated = created,
            ),
        )

    // --- the intent → endpoint → minimal-body table ---

    @Test
    fun setWorkingStateEmitsStatusToken() {
        val request = SetWorkingState(TaskId("a"), WorkingState.Done).toRequest()
        assertEquals(OutboxMethod.Patch, request.method)
        assertEquals(listOf("tasks", "a"), request.path)
        assertEquals("""{"status":"done"}""", request.body)
        // `PATCH tasks/{id}` carries the `activity` ingest sibling (#364), declared on the shared
        // patchTask builder — so every Task field edit inherits it from this one assertion.
        assertTrue(request.acceptsActivityStamp)
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
    fun setDeadlineTimeEmitsHourMinuteClock() {
        // The source-of-truth time axis (#348): an "HH:MM" string on `deadline_time_of_day` alone.
        assertEquals(
            """{"deadline_time_of_day":"09:30"}""",
            SetDeadlineTime(TaskId("a"), LocalTime(9, 30)).toRequest().body,
        )
    }

    @Test
    fun setDeadlineTimeWithNullEmitsExplicitNullForAllDay() {
        // A null time = all-day — an explicit `null` (ADR-0011), distinct from omit, never a dropped field.
        assertEquals(
            """{"deadline_time_of_day":null}""",
            SetDeadlineTime(TaskId("a"), null).toRequest().body,
        )
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

    // --- the soft target date + urgency bucket (#375) ---

    @Test
    fun setTargetDateEmitsOnlyTheSoftDate() {
        // A peer of complete_by on its own key — setting the soft target must never touch the hard
        // deadline, or "I want this sooner" would silently move the real due date.
        assertEquals(
            """{"target_date":"2026-05-20T16:11:42Z"}""",
            SetTargetDate(TaskId("a"), created).toRequest().body,
        )
    }

    @Test
    fun setTargetDateWithNullEmitsExplicitNullToClearIt() {
        // null = "clear it" (ADR-0011), distinct from omit — the server reads target_date as a
        // Patch<T>, where an omitted key means "leave unchanged" and would silently no-op the clear.
        assertEquals("""{"target_date":null}""", SetTargetDate(TaskId("a"), null).toRequest().body)
    }

    @Test
    fun setPriorityEmitsTheLowercaseWireToken() {
        // The readable wire casing, not the domain PascalCase.
        assertEquals("""{"priority":"fire"}""", SetPriority(TaskId("a"), Priority.Fire).toRequest().body)
        assertEquals("""{"priority":"normal"}""", SetPriority(TaskId("a"), Priority.Normal).toRequest().body)
        assertEquals("""{"priority":"backlog"}""", SetPriority(TaskId("a"), Priority.Backlog).toRequest().body)
    }

    @Test
    fun deleteTaskIsBodilessDeleteAndDeclaresNoActivityStamp() {
        val request = DeleteTask(TaskId("a"), created).toRequest()
        assertEquals(OutboxMethod.Delete, request.method)
        assertEquals(listOf("tasks", "a"), request.path)
        assertEquals(null, request.body)
        // Item delete is still a bodiless DELETE upstream — declaring a stamp would push a key onto a
        // route that accepts no entity: a 422, i.e. a dead-lettered delete that leaves the row alive on
        // the server while the UI shows it gone.
        assertFalse(request.acceptsActivityStamp)
    }

    @Test
    fun planAddEmitsTaskIdDateTz() {
        val request = PlanAdd(taskRef("t1"), date, tz).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("items", "plan", "add"), request.path)
        assertEquals("""{"task_id":"t1","date":"2026-06-07","tz":"America/Los_Angeles"}""", request.body)
        // Shared with plan remove + reorder via the postPlan builder.
        assertTrue(request.acceptsActivityStamp)
    }

    @Test
    fun planRemoveEmitsTaskIdDateTz() {
        val request = PlanRemove("t1", date, tz).toRequest()
        assertEquals(listOf("items", "plan", "remove"), request.path)
        assertEquals("""{"task_id":"t1","date":"2026-06-07","tz":"America/Los_Angeles"}""", request.body)
    }

    @Test
    fun planReorderEmitsOrderedTaskIds() {
        val request = PlanReorder(listOf(taskRef("t1"), taskRef("t2")), date, tz).toRequest()
        assertEquals(listOf("items", "plan", "reorder"), request.path)
        assertEquals("""{"task_ids":["t1","t2"],"date":"2026-06-07","tz":"America/Los_Angeles"}""", request.body)
    }

    /**
     * A non-Task ref rides the same wire shape (#385). The body key stays `task_id` — that is the
     * server's spelling for "the item this plan slot points at", not a claim about kind; its handler
     * validates the id with the kind-neutral `is_user_item`. The kind stays client-side, where it is
     * what lets the local resolve find the row in the right cache.
     */
    @Test
    fun aPlanWriteForARecurringItemUsesTheSameWireShape() {
        val add = PlanAdd(PlanItemRef("h1", ItemKind.Habit), date, tz).toRequest()
        assertEquals(listOf("items", "plan", "add"), add.path)
        assertEquals("""{"task_id":"h1","date":"2026-06-07","tz":"America/Los_Angeles"}""", add.body)

        val reorder = PlanReorder(
            listOf(PlanItemRef("h1", ItemKind.Habit), PlanItemRef("c1", ItemKind.Chore), taskRef("t1")),
            date,
            tz,
        ).toRequest()
        assertEquals("""{"task_ids":["h1","c1","t1"],"date":"2026-06-07","tz":"America/Los_Angeles"}""", reorder.body)
    }

    @Test
    fun moveEmitsNewParentAndPosition() {
        val request = Move(id = "x", newParentId = "p", position = 2).toRequest()
        assertEquals(OutboxMethod.Post, request.method)
        assertEquals(listOf("items", "x", "move"), request.path)
        assertEquals("""{"new_parent_id":"p","position":2}""", request.body)
        assertTrue(request.acceptsActivityStamp)
    }

    @Test
    fun moveToRootEmitsExplicitNullParent() {
        // null parent = "detach to root" (ADR-0049), an explicit wire null distinct from omit (ADR-0011).
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

        // All three kind-scoped PATCHes accept the `activity` sibling (#364) — asserted per kind because
        // the endpoint is kind-selected, so a kind that lost its declaration would lose it silently.
        for (request in listOf(habit, chore, event)) {
            assertTrue(request.acceptsActivityStamp, "${request.path} must declare the activity stamp")
        }
    }

    @Test
    fun setDefinitionStateTargetsTheRawItemId() {
        assertEquals("item:h1", SetDefinitionState("h1", ItemKind.Habit, DefinitionState.Archived).target)
    }

    /**
     * One transform where there were three (#422). The light switch was a typed `applyTo` overload per
     * kind, because Habit, Chore and Event are three data classes with no supertype. It is one Family
     * member on one record now, so the kind only picks the endpoint.
     */
    @Test
    fun setDefinitionStateSwapsTheLifecycleAndIsIdempotent() {
        val intent = SetDefinitionState("x", ItemKind.Habit, DefinitionState.Archived)
        val active = definitionItem(state = DefinitionState.Active)

        val archived = intent.applyTo(active)
        assertEquals(Lifecycle.Definition(DefinitionState.Archived), archived.progress.lifecycle)
        assertEquals(archived, intent.applyTo(archived), "applyTo must be idempotent")
        // A swap, not an addition: the family holds one member, so the Active lifecycle is gone rather
        // than sitting beside the Archived one.
        assertEquals(1, archived.plugins.count { it is Progress })
    }

    @Test
    fun targetsPartitionByEntity() {
        assertEquals("task:a", SetWorkingState(TaskId("a"), WorkingState.Done).target)
        assertEquals("task:a", DeleteTask(TaskId("a"), created).target)
        assertEquals("item:x", Move(id = "x", newParentId = null, position = 0).target)
    }

    /**
     * A plan write's target names the **day**, not `(day, zone)` (#385). The server holds one plan per
     * date and reads the zone only to decide which date that is, so `plan:$date:$tz` named a partition
     * that does not exist server-side.
     *
     * Diagnostic metadata only, per [Mutation.target] — replay stays globally FIFO by enqueue sequence,
     * and [coalesceOccurrences] collapses occurrence targets alone, so no plan write is coalesced away
     * by this. Pinned so the target can't quietly re-acquire the zone.
     */
    @Test
    fun planTargetsNameTheDayNotTheZone() {
        assertEquals("plan:2026-06-07", PlanAdd(taskRef("t1"), date, tz).target)
        assertEquals(
            PlanAdd(taskRef("t1"), date, tz).target,
            PlanRemove("t2", date, "Europe/Berlin").target,
            "the same day under a different zone is the same plan",
        )
    }

    // --- optimistic apply: correctness + idempotence (replay-safety) ---

    /**
     * Each intent swaps exactly the Family it names and leaves the rest of the list alone (#422). Title
     * and the tombstone are the two exceptions: both live on `Core`, because a row would still carry
     * them saying nothing about when, how often or how strongly (ADR-0055).
     */
    @Test
    fun taskApplySwapsTheRightFamily() {
        val base = taskItem(state = WorkingState.Open, title = "old")
        assertEquals(
            Lifecycle.Working(WorkingState.Done),
            SetWorkingState(TaskId("a"), WorkingState.Done).applyTo(base).progress.lifecycle,
        )
        assertEquals("new", Rename(TaskId("a"), "new").applyTo(base).core.title)
        assertEquals(
            Anchor.Deadline(completeBy = created),
            SetDeadline(TaskId("a"), created).applyTo(base).anchor,
        )
        assertEquals(
            Anchor.Deadline(timeOfDay = LocalTime(14, 30)),
            SetDeadlineTime(TaskId("a"), LocalTime(14, 30)).applyTo(base).anchor,
        )
        assertEquals("x", SetDescription(TaskId("a"), "x").applyTo(base).describable.description)
        assertEquals(null, ClearDescription(TaskId("a")).applyTo(base).describable.description)
        assertEquals(listOf("home"), SetLabels(TaskId("a"), listOf("home")).applyTo(base).taggable.labels)
        assertTrue(SetPinned(TaskId("a"), true).applyTo(base).priority.pinned)
        assertEquals(created, SetTargetDate(TaskId("a"), created).applyTo(base).targeted.targetDate)
        assertEquals(Priority.Fire, SetPriority(TaskId("a"), Priority.Fire).applyTo(base).priority.priority)
        assertTrue(DeleteTask(TaskId("a"), created).applyTo(base).core.isDeleted)
    }

    /**
     * Clearing a Family's last field **unloads the Family** rather than loading a member equal to its
     * own silence (#422). That is the sparseness rule the recipe round trip rests on: a list holding a
     * plugin that says nothing would make two lists correspond to one row, and the round trip an
     * equivalence rather than an identity. It is why every transform goes through `loading`.
     */
    @Test
    fun clearingTheLastFieldOfAFamilyUnloadsIt() {
        val dated = SetDeadline(TaskId("a"), created).applyTo(taskItem())
        assertEquals(Anchor.Unanchored, ClearDeadline(TaskId("a")).applyTo(dated).anchor)
        assertFalse(ClearDeadline(TaskId("a")).applyTo(dated).plugins.any { it is Anchor })

        // …but only when nothing else in the Family is left. A deadline still carrying its clock time is
        // a deadline, and clearing the instant alone must not take the clock with it.
        val timed = SetDeadlineTime(TaskId("a"), LocalTime(9, 0)).applyTo(dated)
        assertEquals(
            Anchor.Deadline(timeOfDay = LocalTime(9, 0)),
            ClearDeadline(TaskId("a")).applyTo(timed).anchor,
        )
        assertEquals(Anchor.Unanchored, SetDeadlineTime(TaskId("a"), null).applyTo(taskItem()).anchor)
    }

    @Test
    fun taskApplyIsIdempotent() {
        val base = taskItem(state = WorkingState.Open)
        val intents = listOf(
            SetWorkingState(TaskId("a"), WorkingState.Done),
            Rename(TaskId("a"), "x"),
            SetDeadline(TaskId("a"), created),
            ClearDeadline(TaskId("a")),
            SetDeadlineTime(TaskId("a"), LocalTime(9, 0)),
            SetLabels(TaskId("a"), listOf("l")),
            SetPinned(TaskId("a"), true),
            SetTargetDate(TaskId("a"), created),
            SetTargetDate(TaskId("a"), null),
            SetPriority(TaskId("a"), Priority.Backlog),
            DeleteTask(TaskId("a"), created),
        )
        for (intent in intents) {
            val once = intent.applyTo(base)
            assertEquals(once, intent.applyTo(once), "applyTo must be idempotent for $intent")
        }
    }

    @Test
    fun planApplyTransformsAndIsIdempotent() {
        val t1 = taskRef("t1")
        val t2 = taskRef("t2")

        val added = PlanAdd(t1, date, tz)
        assertEquals(listOf(t1), added.applyTo(emptyList()))
        assertEquals(listOf(t1), added.applyTo(listOf(t1)), "add is a no-op when already present")

        val removed = PlanRemove("t1", date, tz)
        assertEquals(listOf(t2), removed.applyTo(listOf(t1, t2)))
        assertEquals(listOf(t2), removed.applyTo(listOf(t2)), "remove is a no-op when already absent")

        val reordered = PlanReorder(listOf(t2, t1), date, tz)
        assertEquals(listOf(t2, t1), reordered.applyTo(listOf(t1, t2)))
        assertEquals(listOf(t2, t1), reordered.applyTo(listOf(t2, t1)), "reorder replays to the same order")
    }

    /**
     * The optimistic apply is keyed on the **id**, never on the whole ref (#385). Presence and removal
     * both have to answer "is this item on the day", and a ref that arrives with a `null` kind (an
     * unrecognised server token) names the same slot as one that arrived tagged — treating those as two
     * different entries would let a plan hold the same item twice, or refuse to remove it.
     */
    @Test
    fun planApplyMatchesOnTheIdRegardlessOfKind() {
        val habit = PlanItemRef("h1", ItemKind.Habit)
        val untagged = PlanItemRef("h1", kind = null)

        assertEquals(
            listOf(untagged),
            PlanAdd(habit, date, tz).applyTo(listOf(untagged)),
            "add is a no-op for an id already present, whatever kind the cached ref carries",
        )
        assertEquals(emptyList(), PlanRemove("h1", date, tz).applyTo(listOf(untagged)))
        assertEquals(listOf(habit, taskRef("t1")), PlanAdd(taskRef("t1"), date, tz).applyTo(listOf(habit)))
    }
}

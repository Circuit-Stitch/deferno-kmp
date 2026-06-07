package com.circuitstitch.deferno.demo

import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlin.time.Instant

/**
 * In-memory sample content **for the shell + Compose-View tests** (#27/#55): the Tasks + Plan
 * Destinations render this through the [DemoTaskRepository] / [DemoPlanRepository] test fakes.
 * Deliberately small and calm (design-principles.md): a couple of pinned/active items, one parent
 * that decomposes into steps, and a short daily plan. The real app reads its data from the
 * DI-provided repositories (#68, ADR-0014); this stays a test fixture.
 */
internal object SampleData {

    private val created = Instant.parse("2026-06-01T09:00:00Z")

    private fun task(
        id: String,
        title: String,
        workingState: WorkingState = WorkingState.Open,
        ref: String? = null,
        parentId: String? = null,
        children: List<String> = emptyList(),
        sequence: Long? = null,
        pinned: Boolean = false,
    ): Task = Task(
        id = TaskId(id),
        orgSlug = "u-deferno",
        title = title,
        workingState = workingState,
        ref = ref,
        parentId = parentId?.let(::TaskId),
        children = children.map(::TaskId),
        sequence = sequence,
        pinned = pinned,
        dateCreated = created,
        hydration = HydrationState.Summary,
    )

    val tasks: List<Task> = listOf(
        task(
            id = "t-1",
            title = "Plan the spring launch",
            workingState = WorkingState.InProgress,
            ref = "u-deferno-1",
            pinned = true,
            children = listOf("t-1a", "t-1b", "t-1c"),
        ),
        task("t-1a", "Draft the announcement", ref = "u-deferno-2", parentId = "t-1", sequence = 1),
        task("t-1b", "Review copy with the team", ref = "u-deferno-3", parentId = "t-1", sequence = 2),
        task("t-1c", "Schedule the post", WorkingState.Done, ref = "u-deferno-4", parentId = "t-1", sequence = 3),
        task("t-2", "Water the plants", ref = "u-deferno-5"),
        task("t-3", "Reply to Sam", WorkingState.InReview, ref = "u-deferno-6"),
        task("t-4", "Old idea worth revisiting", WorkingState.Dropped, ref = "u-deferno-7"),
    )

    /** Today's plan: the items currently worth surfacing, in order (a subset of [tasks]). */
    val planTaskIds: List<TaskId> = listOf(TaskId("t-1"), TaskId("t-2"), TaskId("t-3"))

    /** A gentle placeholder description, applied on hydrate to demonstrate the summary → full upgrade. */
    fun descriptionFor(task: Task): String =
        "“${task.title}” — opened from the in-memory demo. The full task detail (description, owner, " +
            "next step) is hydrated on demand in the real app (#22)."
}

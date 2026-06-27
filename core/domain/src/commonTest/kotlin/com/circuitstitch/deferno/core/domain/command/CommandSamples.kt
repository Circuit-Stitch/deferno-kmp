package com.circuitstitch.deferno.core.domain.command

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import kotlinx.datetime.LocalDate
import kotlin.time.Instant

internal val SAMPLE_DEADLINE: Instant = Instant.parse("2026-06-07T12:00:00Z")
internal val SAMPLE_DATE: LocalDate = LocalDate(2026, 6, 7)
internal const val SAMPLE_TZ: String = "America/Los_Angeles"

/**
 * One representative [Command] instance per [CommandKind]. The `when` is **exhaustive over the
 * catalog**, so adding a [CommandKind] without a sample is a compile error — which keeps the
 * catalog-wide integrity tests (every kind dispatches; every kind's instance reports that kind)
 * honestly covering the *whole* registry rather than a stale subset.
 */
internal fun sampleCommand(kind: CommandKind): Command = when (kind) {
    CommandKind.CompleteTask -> CompleteTask(TaskId("t1"))
    CommandKind.ReopenTask -> ReopenTask(TaskId("t1"))
    CommandKind.DropTask -> DropTask(TaskId("t1"))
    CommandKind.RenameTask -> RenameTask(TaskId("t1"), "new title")
    CommandKind.SetTaskDeadline -> SetTaskDeadline(TaskId("t1"), SAMPLE_DEADLINE)
    CommandKind.ClearTaskDeadline -> ClearTaskDeadline(TaskId("t1"))
    CommandKind.SetTaskDescription -> SetTaskDescription(TaskId("t1"), "a description")
    CommandKind.ClearTaskDescription -> ClearTaskDescription(TaskId("t1"))
    CommandKind.SetTaskLabels -> SetTaskLabels(TaskId("t1"), listOf("home", "errands"))
    CommandKind.SetTaskPinned -> SetTaskPinned(TaskId("t1"), pinned = true)
    CommandKind.DeleteTask -> DeleteTask(TaskId("t1"))
    CommandKind.AddToPlan -> AddToPlan(TaskId("t1"), SAMPLE_DATE, SAMPLE_TZ)
    CommandKind.RemoveFromPlan -> RemoveFromPlan(TaskId("t1"), SAMPLE_DATE, SAMPLE_TZ)
    CommandKind.ReorderPlan -> ReorderPlan(listOf(TaskId("t1")), SAMPLE_DATE, SAMPLE_TZ)
    CommandKind.StartTask -> StartTask(TaskId("t1"))
    CommandKind.SendTaskToReview -> SendTaskToReview(TaskId("t1"))
    CommandKind.OpenTask -> OpenTask(TaskId("t1"))
    CommandKind.CreateItem -> CreateItem(CreateItem.Payload.Task(CreateTaskPayload(title = "new")))
    CommandKind.ConvertItem -> ConvertItem("item-1", ItemKind.Task, ConvertItemPayload(type = "habit"))
    CommandKind.MarkOccurrence -> MarkOccurrence("ce-1", OccurrenceAction.Complete)
    CommandKind.ClearOccurrence -> ClearOccurrence("ce-1")
    CommandKind.RescheduleOccurrence -> RescheduleOccurrence("ce-1", SAMPLE_DATE)
    CommandKind.SetTheme -> SetTheme(ThemeFamily.Mono, ThemeMode.Dark)
    CommandKind.SetTracking -> SetTracking(enabled = true)
    CommandKind.SetDragAndDrop -> SetDragAndDrop(enabled = true)
    CommandKind.SetDoneVisibility -> SetDoneVisibility(259200L, 86400L)
    CommandKind.MoveItem -> MoveItem("item-1", newParentId = "p1", position = 2)
    CommandKind.SetDefinitionState -> SetDefinitionState("h1", ItemKind.Habit, DefinitionState.Archived)
}

/** Concise [Task] fixture for the enablement tests. */
internal fun task(
    id: String = "t1",
    workingState: WorkingState = WorkingState.Open,
    deleted: Boolean = false,
): Task = Task(
    id = TaskId(id),
    orgSlug = "u-test",
    title = "Task $id",
    workingState = workingState,
    dateCreated = Instant.parse("2026-05-01T00:00:00Z"),
    deletedAt = if (deleted) Instant.parse("2026-05-02T00:00:00Z") else null,
)

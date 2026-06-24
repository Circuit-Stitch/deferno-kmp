package com.circuitstitch.deferno.macos.bridge

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent

/**
 * The **Decompose half of the bridge** the SwiftUI Views observe (#51). SKIE (ADR-0003) bridges each
 * component's `StateFlow`/sealed/enum types into idiomatic Swift, so the Views observe those directly
 * (`SkieSwiftStateFlow` → `StateFlowObserver`, `ObservableState.swift`) — including navigation, now that
 * the components expose their Decompose `Value`/`ChildStack`/`ChildSlot` as `StateFlow` mirrors
 * (`Value.asStateFlow`). What remains here is the non-reactive seam SKIE can't synthesize: value-class
 * unwraps + the `Instant`↔epoch codec the SwiftUI `TaskDetailView` can't do itself. (Shell seams live
 * in `ShellBridge.kt`.)
 */

/** Whether a row's [kind] is a Task — the only kind with a detail surface today (the trailing `›`). */
fun itemKindIsTask(kind: ItemKind): Boolean = kind == ItemKind.Task

/**
 * A stable String identity for a [Task], for SwiftUI list diffing. `Task.id` is a [TaskId] value
 * class that Kotlin/Native erases to an opaque `id` in the header (so Swift can't read `.value`); this
 * unwraps it to the underlying UUID String the View keys rows on.
 */
fun taskKey(task: Task): String = task.id.value

/** The String identity of the Task a detail pane shows — for SwiftUI view identity (see [taskKey]). */
fun detailKey(component: TaskDetailComponent): String = component.taskId.value

// ---------------------------------------------------------------------------------------------------
// Task detail PROPERTIES + subtask drill (#195) — value-class unwraps + the Instant↔epoch codec the
// SwiftUI TaskDetailView can't do itself (TaskId/OrgId are header-erased, Instant is opaque). The
// Due/Labels writes land through the real outbox seams the shared MainShellComponent wires
// (AccountSession), so editing here persists offline-first (ADR-0001).
// ---------------------------------------------------------------------------------------------------

/** Open a subtask's own detail (the row's title/chevron) — Swift holds the erased [Task.id], so Kotlin reads it. */
fun openSubtask(component: TaskDetailComponent, subtask: Task) = component.onSubtaskClicked(subtask.id)

/** The current deadline as Unix epoch seconds for a SwiftUI `DatePicker`, or -1.0 when the Task has none. */
fun taskDeadlineEpochSeconds(task: Task): Double =
    task.completeBy?.let { it.toEpochMilliseconds() / 1000.0 } ?: -1.0

/** Set the deadline DUE date from a `DatePicker` selection (epoch seconds → the device-zone calendar day). */
fun setTaskDeadline(component: TaskDetailComponent, epochSeconds: Double) {
    val day = Instant.fromEpochMilliseconds((epochSeconds * 1000).toLong())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    component.onSetDeadline(day)
}

/** Clear the deadline DUE date (the explicit clear path). */
fun clearTaskDeadline(component: TaskDetailComponent) = component.onSetDeadline(null)

/** Read-only PROPERTIES labels for the Swift view — the opaque-typed fields it can't format itself. */
fun taskTimeLabel(task: Task): String = task.deadlineTimeOfDay?.toString() ?: "—"
fun taskOwnerLabel(task: Task): String = task.ownerOrgId?.value ?: "—"

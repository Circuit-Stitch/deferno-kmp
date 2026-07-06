package com.circuitstitch.deferno.macos.bridge

import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.circuitstitch.deferno.feature.tasks.ActivityItem
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailState

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

// ---------------------------------------------------------------------------------------------------
// ACTIVITY feed (ADR-0043) — the macOS twin of the iOS bridge's comment/activity seam. macOS newly gains
// the ACTIVITY section, so these are added here (kept IDENTICAL to app/iosApp .../ios/bridge/Bridge.kt).
// The sealed [ActivityItem] is cracked open with the app's manual-discriminator idiom: Swift keys ForEach
// on [activityItemId], unwraps a comment via [activityItemComment] (nil ⇒ history), and renders a history
// row from the verb token + date label. UserId is header-erased (Swift can't ==) and Instant can't be
// formatted in Swift, so those stay here.
// ---------------------------------------------------------------------------------------------------

/** Whether [comment] is the current user's (gates inline Edit/Delete) — both ids are erased [UserId]s. */
fun commentIsMine(state: TaskDetailState, comment: Comment): Boolean {
    val me = state.currentUserId ?: return false
    return comment.createdBy == me
}

/** A comment's display date (e.g. "2026-04-17 (edited)") — Instant formatting Swift can't do directly. */
fun commentDateLabel(comment: Comment): String =
    comment.createdAt.toString().substringBefore('T') + if (comment.editedAt != null) " (edited)" else ""

/** A stable Swift-visible row id for `ForEach(id:)` — the comment id, or "history:<index>". */
fun activityItemId(item: ActivityItem): String = item.id

/** The comment an [item] wraps, or `nil` for a history row (the `as?` discriminator). */
fun activityItemComment(item: ActivityItem): Comment? = (item as? ActivityItem.Comment)?.comment

/** A history row's typed verb token (Swift maps it to a localized label), or `nil` for a comment row. */
fun activityHistoryVerb(item: ActivityItem): String? =
    (item as? ActivityItem.HistoryEvent)?.event?.let(::historyVerbToken)

/** A history row's display date ("2026-04-17"), or `nil` for a comment — Instant formatting Swift can't do. */
fun activityHistoryDateLabel(item: ActivityItem): String? =
    (item as? ActivityItem.HistoryEvent)?.event?.recordedAt?.toString()?.substringBefore('T')

/** The stable verb token per history variant — kept in sync with the Compose `label()` mapping (ADR-0043). */
private fun historyVerbToken(event: ItemHistoryEvent): String = when (event) {
    is ItemHistoryEvent.Created -> "Created"
    is ItemHistoryEvent.Updated -> "Updated"
    is ItemHistoryEvent.StatusChanged -> "StatusChanged"
    is ItemHistoryEvent.Moved -> "Moved"
    is ItemHistoryEvent.ParentAssigned -> "ParentAssigned"
    is ItemHistoryEvent.Split -> "Split"
    is ItemHistoryEvent.FoldedInto -> "FoldedInto"
    is ItemHistoryEvent.MergedChild -> "MergedChild"
    is ItemHistoryEvent.MergedIntoParent -> "MergedIntoParent"
    is ItemHistoryEvent.Unknown -> "Unknown"
}

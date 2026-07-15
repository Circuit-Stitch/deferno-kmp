package com.circuitstitch.deferno.ios.bridge

import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.JourneyLabel
import com.circuitstitch.deferno.core.model.JourneyStyle
import com.circuitstitch.deferno.core.model.RelativeDay
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.journeyStatus
import com.circuitstitch.deferno.core.model.relativeDay
import com.circuitstitch.deferno.feature.tasks.ActivityItem
import com.circuitstitch.deferno.feature.tasks.ParentSummary
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.time.Instant

/**
 * The **Decompose half of the bridge** the SwiftUI Views observe (#51). SKIE (ADR-0003) bridges each
 * component's `StateFlow`/sealed/enum types into idiomatic Swift, so the Views observe those directly
 * (`SkieSwiftStateFlow` → `StateFlowObserver`, `ObservableState.swift`) — including navigation, now that
 * the components expose their Decompose `Value`/`ChildStack`/`ChildSlot` as `StateFlow` mirrors
 * (`Value.asStateFlow`). What remains here is the non-reactive seam SKIE can't synthesize: value-class
 * unwraps, sealed `as?` discriminators, and `NSData`/`Instant` codecs. (Shell seams live in `ShellBridge.kt`.)
 */

/** True when [kind] is the Task kind — Swift can't reliably `==` a bridged Kotlin enum in a static framework. */
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
// Task detail sections (#207 iOS parity) — the value-class unwraps + NSData/Instant codecs the SwiftUI
// TaskDetailView can't do itself: TaskId/UserId/OrgId are header-erased, ByteArray/Instant are opaque.
// ---------------------------------------------------------------------------------------------------

/** Open a subtask's own detail (the row chevron) — Swift holds the erased [Task.id], so Kotlin reads it. */
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
// Connected-parent header + journey-status + relative-day readings (ADR-0044). Kept IDENTICAL to
// app/macosApp .../macos/bridge/Bridge.kt. `JourneyStatus`/`RelativeDay` are pure readings in core/model
// (Compose-free, iOS-safe); Swift can't `==` a bridged enum in a static framework, so these expose
// stable String tokens the SwiftUI View maps to `L` strings — the same idiom as [historyVerbToken].
// ---------------------------------------------------------------------------------------------------

/** Tap the connected-parent node → push the parent's own detail (reuses the subtask-drill seam). */
fun openParent(component: TaskDetailComponent, parent: ParentSummary) = component.onSubtaskClicked(parent.id)

/** The active journey slot for the 3-slot indicator: Initial=0, Middle=1, Terminal=2. */
fun journeyActiveSlot(task: Task): Int = task.journeyStatus().slot.ordinal

/** The stable journey-label token — Swift maps it to a `tasks_journey_*` string via `L.journeyLabel`. */
fun journeyLabelToken(task: Task): String = when (task.journeyStatus().label) {
    JourneyLabel.ToDo -> "TODO"
    JourneyLabel.InProgress -> "IN_PROGRESS"
    JourneyLabel.InReview -> "IN_REVIEW"
    JourneyLabel.Done -> "DONE"
    JourneyLabel.NotDoing -> "NOT_DOING"
    JourneyLabel.Blocked -> "BLOCKED"
}

/** Whether the reading is the shelved (NOT DOING) style — the dashed tail + struck-through DONE. */
fun journeyIsShelved(task: Task): Boolean = task.journeyStatus().style == JourneyStyle.NotDoing

/** Whether the reading is the blocked style — the error-tone middle slot. */
fun journeyIsBlocked(task: Task): Boolean = task.journeyStatus().style == JourneyStyle.Blocked

/**
 * The relative-day token for the WHEN row over [Task.completeBy], or `null` when the Task has no
 * deadline: `TODAY | TOMORROW | YESTERDAY | DAYS_AWAY | DAYS_AGO`. Swift maps it (with [taskDueRelativeCount])
 * to a `tasks_detail_due_*` string via `L.relativeDay`.
 */
fun taskDueRelativeToken(task: Task): String? = task.completeBy?.let { instant ->
    when (relativeDay(instant)) {
        RelativeDay.Today -> "TODAY"
        RelativeDay.Tomorrow -> "TOMORROW"
        RelativeDay.Yesterday -> "YESTERDAY"
        is RelativeDay.DaysAway -> "DAYS_AWAY"
        is RelativeDay.DaysAgo -> "DAYS_AGO"
    }
}

/** The day count for the `DAYS_AWAY`/`DAYS_AGO` plural (else 0) — feeds `L.relativeDay(token, count)`. */
fun taskDueRelativeCount(task: Task): Int = task.completeBy?.let { instant ->
    when (val r = relativeDay(instant)) {
        is RelativeDay.DaysAway -> r.days
        is RelativeDay.DaysAgo -> r.days
        else -> 0
    }
} ?: 0

/** Whether [comment] is the current user's (gates inline Edit/Delete) — both ids are erased [UserId]s. */
fun commentIsMine(state: TaskDetailState, comment: Comment): Boolean {
    val me = state.currentUserId ?: return false
    return comment.createdBy == me
}

/** A comment's display date (e.g. "2026-04-17 (edited)") — Instant formatting Swift can't do directly. */
fun commentDateLabel(comment: Comment): String =
    comment.createdAt.toString().substringBefore('T') + if (comment.editedAt != null) " (edited)" else ""

// --- ACTIVITY feed (ADR-0043): the sealed [ActivityItem] cracked open for Swift with the app's
// manual-discriminator idiom (Swift can't match a Kotlin sealed type or format an Instant — same seam as
// ShellBridge's inboxNote*/activitySummary*). The View keys ForEach on [activityItemId], unwraps a
// comment via [activityItemComment] (nil ⇒ history), and renders a history row from the verb token +
// date label. commentIsMine/commentDateLabel keep taking the unwrapped [Comment].

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

/**
 * Upload a file the iOS picker resolved to this Task (#207). Swift can't build an [AttachmentUpload]
 * (its `bytes` is a Kotlin `ByteArray`), so it passes the picked file's [data] as `NSData` and this
 * copies it across — the same `NSData`→`ByteArray` idiom as `feedbackAddAttachment`.
 */
@OptIn(ExperimentalForeignApi::class)
fun addTaskAttachment(component: TaskDetailComponent, filename: String, contentType: String, data: NSData) {
    val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt()) ?: ByteArray(0)
    component.onAddAttachments(listOf(AttachmentUpload(filename = filename, contentType = contentType, bytes = bytes)))
}

/**
 * The reverse of `addTaskAttachment`'s `NSData`→`ByteArray`: copy a Kotlin [ByteArray] into an `NSData`
 * for Swift. `internal` (not `private`) so the sibling `ShellBridge.kt` export bridge can reuse it (#313).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * Read an **on-device** attachment's bytes for playback (#272) — the retained brain-dump recording the synced
 * `attachments` path never has. The repository read is local + quick, so it runs in a one-shot
 * [Dispatchers.Main] coroutine; [onData] then gets the bytes as `NSData` for
 * `AVAudioPlayer`, or `null` if the row was already deleted. `TaskDetailState.onDeviceAttachments` and
 * `TaskDetailComponent.onDeleteOnDeviceAttachment` are plain enough that Swift reads/calls them directly.
 */
fun onDeviceAttachmentData(component: TaskDetailComponent, attachmentId: String, onData: (NSData?) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        onData(component.onDeviceAttachmentBytes(attachmentId)?.toNSData())
    }
}

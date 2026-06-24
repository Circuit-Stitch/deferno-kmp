package com.circuitstitch.deferno.ios.bridge

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
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
 * The **Decompose half of the bridge** the SwiftUI Views observe (#51). SKIE (ADR-0003) now bridges
 * each component's `StateFlow`/sealed/enum types into idiomatic Swift, so the Views observe those
 * directly (`SkieSwiftStateFlow` → `StateFlowObserver`, `ObservableState.swift`). SKIE does **not**
 * bridge the Decompose reactive types (`Value`/`ChildSlot`/`ChildStack`), so these small **concrete**
 * wrappers keep doing that job by hand: they keep the Decompose generics out of the Swift-facing
 * surface, exposing only a `current`/`active` snapshot and a callback-based `subscribe` that returns a
 * [Subscription] Swift cancels in `deinit`. (The shell's navigation containers live in `ShellBridge.kt`.)
 */

/** A handle Swift holds and [cancel]s in `deinit` to stop observing a Decompose [Value]. */
class Subscription internal constructor(private val onCancel: () -> Unit) {
    fun cancel() {
        onCancel()
    }
}

/**
 * Observes a Decompose [Value] (e.g. the Tasks `activePane`) from Swift. Decompose's `subscribe`
 * fires synchronously with the current value, so no coroutine is involved — the [Subscription] just
 * cancels the Decompose subscription.
 */
class ValueBridge<T : Any> internal constructor(private val delegate: Value<T>) {
    val current: T get() = delegate.value

    fun subscribe(onEach: (T) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it) }
        return Subscription { cancellation.cancel() }
    }
}

/**
 * The Tasks **detail** co-resident slot, flattened to its (nullable) child component so Swift never
 * touches the `Value<ChildSlot<*, …>>` generics. [current] is the open detail component, or `null`.
 */
class DetailSlot internal constructor(private val slot: Value<ChildSlot<*, TaskDetailComponent>>) {
    val current: TaskDetailComponent? get() = slot.value.child?.instance

    fun subscribe(onEach: (TaskDetailComponent?) -> Unit): Subscription {
        val cancellation = slot.subscribe { onEach(it.child?.instance) }
        return Subscription { cancellation.cancel() }
    }
}

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

/** Whether [comment] is the current user's (gates inline Edit/Delete) — both ids are erased [UserId]s. */
fun commentIsMine(state: TaskDetailState, comment: Comment): Boolean {
    val me = state.currentUserId ?: return false
    return comment.createdBy == me
}

/** A comment's display date (e.g. "2026-04-17 (edited)") — Instant formatting Swift can't do directly. */
fun commentDateLabel(comment: Comment): String =
    comment.createdAt.toString().substringBefore('T') + if (comment.editedAt != null) " (edited)" else ""

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

/** The reverse of `addTaskAttachment`'s `NSData`→`ByteArray`: copy a Kotlin [ByteArray] into an `NSData` for Swift. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * Read an **on-device** attachment's bytes for playback (#272) — the retained brain-dump recording the synced
 * `attachments` path never has. The repository read is local + quick, so it runs in a one-shot
 * [Dispatchers.Main] coroutine (matching [StateFlowBridge]); [onData] then gets the bytes as `NSData` for
 * `AVAudioPlayer`, or `null` if the row was already deleted. `TaskDetailState.onDeviceAttachments` and
 * `TaskDetailComponent.onDeleteOnDeviceAttachment` are plain enough that Swift reads/calls them directly.
 */
fun onDeviceAttachmentData(component: TaskDetailComponent, attachmentId: String, onData: (NSData?) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        onData(component.onDeviceAttachmentBytes(attachmentId)?.toNSData())
    }
}

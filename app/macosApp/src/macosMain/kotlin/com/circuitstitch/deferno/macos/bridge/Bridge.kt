package com.circuitstitch.deferno.macos.bridge

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.plan.PlanState
import com.circuitstitch.deferno.feature.tasks.ItemTreeComponent
import com.circuitstitch.deferno.feature.tasks.ItemTreeState
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The **SKIE-free bridge** the SwiftUI Views observe (#51). ADR-0003 calls for SKIE to turn
 * `StateFlow`/`Value`/sealed types into idiomatic Swift, but no released SKIE supports Kotlin 2.4.0
 * yet (see `app/iosApp/README.md`). Until it ships, these small **concrete** wrappers do that job by
 * hand: they keep `kotlinx.coroutines.StateFlow` and the Decompose `Value`/`ChildSlot` generics out
 * of the Swift-facing surface entirely, exposing only `value`/`current` snapshots and a
 * callback-based `subscribe` that returns a [Subscription] Swift cancels in `deinit`
 * (see `iosApp/Bridge/ObservableState.swift`). When SKIE lands, this whole package can be deleted and
 * the Views can observe the components' `StateFlow`/`Value` directly.
 */

/** A handle Swift holds and [cancel]s in `deinit` to stop observing. */
class Subscription internal constructor(private val onCancel: () -> Unit) {
    fun cancel() {
        onCancel()
    }
}

/**
 * Observes a component's `StateFlow` from Swift without SKIE. Each [subscribe] collects on
 * [Dispatchers.Main] (so the View updates on the main thread) in its own scope, cancelled when the
 * returned [Subscription] is. The current value is always available synchronously via [value].
 */
class StateFlowBridge<T : Any> internal constructor(private val flow: StateFlow<T>) {
    val value: T get() = flow.value

    fun subscribe(onEach: (T) -> Unit): Subscription {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { flow.collect { onEach(it) } }
        return Subscription { scope.cancel() }
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

// Concrete factories that pin each StateFlow's element type, so Swift gets a strongly-typed
// StateFlowBridge<…> (the generic is resolved here) without ever naming `StateFlow`. They also keep
// StateFlowBridge's constructor internal — Swift can only obtain a bridge through these.

/** The Tasks **Item tree** pane state (ADR-0034, #227) — the flattened, depth-indented row list. */
fun itemTreeStateBridge(component: ItemTreeComponent): StateFlowBridge<ItemTreeState> =
    StateFlowBridge(component.state)

/** Whether a row's [kind] is a Task — the only kind with a detail surface today (the trailing `›`). */
fun itemKindIsTask(kind: ItemKind): Boolean = kind == ItemKind.Task

fun taskDetailStateBridge(component: TaskDetailComponent): StateFlowBridge<TaskDetailState> =
    StateFlowBridge(component.state)

fun planStateBridge(component: PlanComponent): StateFlowBridge<PlanState> =
    StateFlowBridge(component.state)

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
// SwiftUI TaskDetailView can't do itself (TaskId/OrgId are header-erased, Instant is opaque). Ported
// from the iOS bridge; this target trims it to the Properties + subtask-open seams #195 needs (the
// attachment/comment helpers are #197). The Due/Labels writes land through the real outbox seams the
// shared MainShellComponent wires (AccountSession), so editing here persists offline-first (ADR-0001).
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

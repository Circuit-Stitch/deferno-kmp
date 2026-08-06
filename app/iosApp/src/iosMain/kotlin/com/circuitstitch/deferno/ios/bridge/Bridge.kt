package com.circuitstitch.deferno.ios.bridge

import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.DayFiring
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.JourneyLabel
import com.circuitstitch.deferno.core.model.JourneyStyle
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.RelativeDay
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TodayOccurrence
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.journeyStatus
import com.circuitstitch.deferno.core.model.relativeDay
import com.circuitstitch.deferno.feature.tasks.ActivityItem
import com.circuitstitch.deferno.feature.tasks.DefinitionDetailComponent
import com.circuitstitch.deferno.feature.tasks.ParentSummary
import com.circuitstitch.deferno.feature.tasks.TaskDetailComponent
import com.circuitstitch.deferno.feature.tasks.TaskDetailState
import com.circuitstitch.deferno.feature.tasks.TasksComponent
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
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
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

/**
 * The String identity of the Task a detail pane shows — for SwiftUI view identity (see [taskKey]).
 *
 * **Deliberately unchanged by #383**, and deliberately still `TaskDetailComponent`-typed. Eight Swift
 * call sites feed this to `.id(…)`, and SwiftUI identity fails *silently* when it changes — a re-key
 * carries stale `@State` rather than failing to compile. [detailChildKey] below is its widened sibling
 * for the two-armed Tasks slot, not its replacement; the Plan stack's Task-only detail still keys here.
 */
fun detailKey(component: TaskDetailComponent): String = component.taskId.value

// ---------------------------------------------------------------------------------------------------
// The Tasks detail slot's TWO arms (#383). `TasksComponent.DetailChild` is a Kotlin sealed interface,
// which Swift cannot take apart: a bridged sealed type arrives as an opaque protocol it can neither
// `==` nor pattern-match, so the house idiom is a flat `as?` accessor per arm (the same seam
// `ShellBridge.planChildDashboard`/`planChildDetail` already are for the Plan stack's sealed child).
//
// The accessors take a NULLABLE child so a View can pipe `activeDetail`'s optional straight through
// without an `if let` per arm — an open slot is the exception on this screen, not the rule.
// ---------------------------------------------------------------------------------------------------

/** The open Task detail, or `nil` when a recurring definition is open (or nothing is). */
fun taskDetailOrNull(child: TasksComponent.DetailChild?): TaskDetailComponent? = child?.asTask

/** The open recurring-definition detail (Habit/Chore/Event), or `nil` when a Task is open (or nothing is). */
fun definitionDetailOrNull(child: TasksComponent.DetailChild?): DefinitionDetailComponent? = child?.asDefinition

/**
 * The SwiftUI view identity of whichever detail is open — [detailKey]'s widened sibling.
 *
 * The **definition arm is keyed on its whole [ItemRef], kind included**, and that is not decoration: the
 * two arms share one `.id(…)` namespace, so a bare id would let a Task and a Habit that happen to carry
 * the same UUID land on the same identity and inherit each other's `@State`. Nothing in the wire
 * *promises* those namespaces are disjoint, and a collision here would show up as a detail rendering
 * another item's scroll position and open sheets — not as a crash. The Task arm delegates to [detailKey]
 * unchanged, so a Task's identity string is the same value whichever seam produced it (raw UUIDs never
 * collide with the `Kind:uuid` shape above them).
 */
fun detailChildKey(child: TasksComponent.DetailChild): String = when (child) {
    is TasksComponent.DetailChild.Task -> detailKey(child.component)
    is TasksComponent.DetailChild.Definition ->
        "${child.component.ref.kind.name}:${child.component.ref.id}"
}

// ---------------------------------------------------------------------------------------------------
// The recurring detail's TODAY cell (#383, ADR-0053 decision 4) — the one reading on that surface Swift
// cannot derive at all, because `DayFiring` is a sealed hierarchy.
// ---------------------------------------------------------------------------------------------------

/**
 * Today's cell for a recurring definition, resolved once: which catalog key it reads, and how to render
 * it. One value rather than three accessors, for the reason `RecurrenceLineTokens` gives — the arms are
 * decided together and must not be re-derived apart.
 */
data class DefinitionTodayCell(
    /** The string-catalog key the cell renders. */
    val token: String,
    /**
     * Whether [token] is the [[Occurrence state]] reading (render the chip the agenda uses) rather than
     * one of the three grid arms (a plain muted line — those say something about the *schedule*, not
     * about how a firing went, and a status pill would dress them as one).
     */
    val isState: Boolean,
    /** Whether the chip takes the success tint — see `ShellBridge.occurrenceStatusIsDone` for the rule. */
    val isDone: Boolean,
)

/**
 * The ADR-0053 honesty contract for the Today row, as one Kotlin `when` — the mapping the detail must
 * not paraphrase.
 *
 * The two halves of [TodayOccurrence] answer genuinely different questions ("does anything fire today"
 * vs "how did today go"), and three grid answers have no word in the `common_status_*` vocabulary:
 *
 * - [DayFiring.NotFiring] — the grid **was** reproduced and puts nothing on today. A fact, so it is said.
 * - A **cancelled** firing — the slot existed and was called off, which is a different statement from
 *   the rule never having fired ([DayFiring.Fires] carries it rather than degrading to `NotFiring`).
 * - [DayFiring.Unavailable] with nothing synced — this device cannot reproduce the grid (a `Custom`
 *   rule, a cadence this build cannot model, or a backend-elided `series` block). **Never rendered as
 *   "not scheduled today"**: that claims a fact we do not have, and is the exact conflation
 *   [DayFiring]'s three arms exist to prevent.
 *
 * An `Unavailable` grid whose day *was* synced still shows the stored reading — the fact is real even
 * though the grid is not — which is why the third arm tests the state as well as the firing.
 */
fun definitionTodayCell(today: TodayOccurrence): DefinitionTodayCell {
    // Bound to a local: `TodayOccurrence.firing` is a public `val` from another module, so it does not
    // smart-cast in place.
    val firing = today.firing
    return when {
        firing is DayFiring.NotFiring ->
            DefinitionTodayCell("tasks_detail_today_not_firing", isState = false, isDone = false)
        firing is DayFiring.Fires && firing.firing.isCancelled ->
            DefinitionTodayCell("tasks_detail_today_cancelled", isState = false, isDone = false)
        firing is DayFiring.Unavailable && today.state == OccurrenceState.Unknown ->
            DefinitionTodayCell("tasks_detail_today_unavailable", isState = false, isDone = false)
        else -> DefinitionTodayCell(
            token = occurrenceStateToken(today.state),
            isState = true,
            // The same rule as the agenda chip, read off the same enum: the label splits "done" on
            // punctuality and the tint does not.
            isDone = today.state == OccurrenceState.DoneOnTime || today.state == OccurrenceState.DoneLate,
        )
    }
}

// ---------------------------------------------------------------------------------------------------
// Task detail sections (#207 iOS parity) — the value-class unwraps + NSData/Instant codecs the SwiftUI
// TaskDetailView can't do itself: TaskId/UserId/OrgId are header-erased, ByteArray/Instant are opaque.
// ---------------------------------------------------------------------------------------------------

/** Open a subtask's own detail (the row chevron) — Swift holds the erased [Task.id], so Kotlin reads it. */
fun openSubtask(component: TaskDetailComponent, subtask: Task) = component.onSubtaskClicked(subtask.id)

/** Set the deadline DUE date from a `DatePicker` selection (epoch seconds → the device-zone calendar day). */
fun setTaskDeadline(component: TaskDetailComponent, epochSeconds: Double) {
    val day = Instant.fromEpochMilliseconds((epochSeconds * 1000).toLong())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    component.onSetDeadline(day)
}

/** Clear the deadline DUE date (the explicit clear path). */
fun clearTaskDeadline(component: TaskDetailComponent) = component.onSetDeadline(null)

// --- Combined date+time WHEN picker (#348) — iOS. The deadline is two axes (CONTRACT-NOTES): the
// `complete_by` DATE (the server discards its clock) and the source-of-truth `deadlineTimeOfDay` CLOCK
// (null = all-day). The SwiftUI graphical `[.date, .hourAndMinute]` picker needs a seed instant that
// carries a *real* time and a setter that dispatches only the changed axis. --------------------------

/**
 * The seed clock the combined picker shows for an **all-day** Task (no [Task.deadlineTimeOfDay]) — 9:00 AM,
 * matching the create form's "Add time" default. [applyDeadlinePicker] compares against this same value so an
 * all-day Task stays all-day on a pure date change (only an explicit clock move converts it to timed).
 */
internal val PICKER_DEFAULT_TIME: LocalTime = LocalTime(9, 0)

/** Whether the deadline carries a real clock time (#348); `false` = all-day (show date only, offer "add"/"clear time"). */
fun taskDeadlineHasTime(task: Task): Boolean = task.deadlineTimeOfDay != null

/**
 * The seed instant (epoch seconds) for the combined `[.date, .hourAndMinute]` `DatePicker`: the deadline DUE
 * date combined with the real [Task.deadlineTimeOfDay] (or [PICKER_DEFAULT_TIME] when all-day), at the device
 * zone. `-1.0` when the Task has no deadline. Deliberately NOT raw `completeBy`, whose clock is the end-of-day
 * sentinel (23:59:59) for an all-day Task and must never seed a time row — that is why the raw accessor this
 * replaced no longer exists.
 */
fun taskDeadlinePickerEpochSeconds(task: Task): Double {
    val by = task.completeBy ?: return -1.0
    val zone = TimeZone.currentSystemDefault()
    val date = by.toLocalDateTime(zone).date
    val time = task.deadlineTimeOfDay ?: PICKER_DEFAULT_TIME
    return date.atTime(time).toInstant(zone).toEpochMilliseconds() / 1000.0
}

/**
 * Apply a combined date+time picker selection, dispatching **only the changed axis** (#348): a changed DAY
 * forwards [TaskDetailComponent.onSetDeadline] (date axis); a changed clock forwards
 * [TaskDetailComponent.onSetDeadlineTime] (the source-of-truth time axis). The current values are read live
 * from the component's state (not a Swift snapshot) so rapid edits within one open popover stay correct.
 * Comparing the picked clock against the seed (real time, or [PICKER_DEFAULT_TIME] when all-day) is what keeps
 * an all-day Task all-day on a pure date change — and converts it to timed only when the user moves the clock.
 */
fun applyDeadlinePicker(component: TaskDetailComponent, epochSeconds: Double) {
    val task = component.state.value.task ?: return
    val zone = TimeZone.currentSystemDefault()
    val picked = Instant.fromEpochMilliseconds((epochSeconds * 1000).toLong()).toLocalDateTime(zone)
    if (picked.date != task.completeBy?.toLocalDateTime(zone)?.date) component.onSetDeadline(picked.date)
    val seedTime = task.deadlineTimeOfDay ?: PICKER_DEFAULT_TIME
    if (picked.hour != seedTime.hour || picked.minute != seedTime.minute) {
        component.onSetDeadlineTime(LocalTime(picked.hour, picked.minute))
    }
}

/** Set the deadline clock time to [PICKER_DEFAULT_TIME] — the "add a time" affordance for an all-day Task (#348). */
fun addTaskDeadlineTime(component: TaskDetailComponent) = component.onSetDeadlineTime(PICKER_DEFAULT_TIME)

/** Clear the deadline clock time → all-day (#348); the DUE date stays. */
fun clearTaskDeadlineTime(component: TaskDetailComponent) = component.onSetDeadlineTime(null)

// --- The SOFT target date (#375) — iOS. A peer of the hard deadline above and fully INDEPENDENT of it:
// any combination is valid, and writing one never moves the other. It is **date-granular** — there is no
// target time-of-day — so unlike the deadline it has ONE axis, and its picker is date-only. The
// component owns the day→instant conversion (inclusive end of day at the device zone), so these seams
// only ever hand it a `LocalDate`. --------------------------------------------------------------------

/**
 * The clock the date-only target picker anchors a stored target on. Midday, deliberately: the stored
 * instant is the day's inclusive END, and re-anchoring at noon keeps the seed comfortably inside the day
 * in every zone and across a DST jump (a midnight anchor can be a nonexistent local time). The picker
 * shows no clock, so this value is never read by a human — it only has to land on the right *day*.
 */
private val TARGET_PICKER_ANCHOR_TIME: LocalTime = LocalTime(12, 0)

/**
 * The seed instant (epoch seconds) for the date-only target `DatePicker` — the stored [Task.targetDate]
 * re-anchored to midday of its device-zone day (see [TARGET_PICKER_ANCHOR_TIME]). `-1.0` when the Task
 * carries no soft target, the same out-of-band "unset" sentinel the deadline seam and
 * `OptionalDatePickerRow` use.
 */
fun taskTargetDatePickerEpochSeconds(task: Task): Double {
    val target = task.targetDate ?: return -1.0
    val zone = TimeZone.currentSystemDefault()
    return target.toLocalDateTime(zone).date
        .atTime(TARGET_PICKER_ANCHOR_TIME)
        .toInstant(zone)
        .toEpochMilliseconds() / 1000.0
}

/**
 * Set the soft target date from a `DatePicker` selection (epoch seconds → the device-zone calendar day).
 * Only the DAY crosses: [TaskDetailComponent.onSetTargetDate] takes a `LocalDate` and decides the instant
 * itself, so a stray clock in the picked value can never invent a target time-of-day.
 */
fun setTaskTargetDate(component: TaskDetailComponent, epochSeconds: Double) {
    val day = Instant.fromEpochMilliseconds((epochSeconds * 1000).toLong())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    component.onSetTargetDate(day)
}

/** Clear the soft target date (the explicit clear path). Leaves the hard deadline untouched. */
fun clearTaskTargetDate(component: TaskDetailComponent) = component.onSetTargetDate(null)

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
// comment via [activityItemComment] (nil ⇒ history), and renders a history row from the enriched
// [activityHistoryLine] + [activityHistoryGlyph] below (ADR-0046). commentIsMine/commentDateLabel keep
// taking the unwrapped [Comment].

/** A stable Swift-visible row id for `ForEach(id:)` — the comment id, or "history:<index>". */
fun activityItemId(item: ActivityItem): String = item.id

/** The comment an [item] wraps, or `nil` for a history row (the `as?` discriminator). */
fun activityItemComment(item: ActivityItem): Comment? = (item as? ActivityItem.Comment)?.comment

/**
 * The device-local ISO day key (yyyy-MM-dd) this Trail row buckets under — the day-group key AND the
 * "TODAY" test. Instant→zoned day is the one piece Swift can't reproduce without re-deriving the zone;
 * matches Compose `it.at.localDayIso()`. Pure kotlinx.datetime (no java.time).
 */
fun activityItemDayIso(item: ActivityItem): String =
    item.at.toLocalDateTime(TimeZone.currentSystemDefault()).date.toString()

/** This row's instant as Unix epoch seconds — Swift renders the row time + diff subtitle via DateFormatter. */
fun activityItemEpochSeconds(item: ActivityItem): Double =
    item.at.toEpochMilliseconds() / 1000.0

/**
 * The leading unicode kind glyph for a history row (decorative, dependency-free), or null for a comment
 * row (Swift renders 💬 for comments). Same table as Compose `historyGlyph`.
 */
fun activityHistoryGlyph(item: ActivityItem): String? {
    val event = (item as? ActivityItem.HistoryEvent)?.event ?: return null
    return when (event) {
        is ItemHistoryEvent.Created -> "●"          // ●
        is ItemHistoryEvent.Updated -> "✎"          // ✎
        is ItemHistoryEvent.StatusChanged -> "↻"    // ↻
        is ItemHistoryEvent.Split -> "✂"            // ✂
        is ItemHistoryEvent.Moved -> "→"            // →
        is ItemHistoryEvent.ParentAssigned -> "◈"   // ◈
        is ItemHistoryEvent.FoldedInto -> "≡"       // ≡
        is ItemHistoryEvent.MergedChild -> "≡"      // ≡
        is ItemHistoryEvent.MergedIntoParent -> "≡" // ≡
        is ItemHistoryEvent.Unknown -> "…"          // …
    }
}

// The narrow humanize map for the UPDATED-fields summary — matches the Compose `fieldLabel` in
// TaskDetailSections (which recognizes ONLY these four; status/pinned fall through to the generic line,
// even though ActivityField.fromKey maps them). Do NOT use ActivityField.fromKey here.
private val UpdatedFieldToken: Map<String, String> = mapOf(
    "title" to "TITLE",
    "description" to "DESCRIPTION",
    "notes" to "DESCRIPTION",
    "deadline" to "DEADLINE",
    "complete_by" to "DEADLINE",
    "labels" to "LABELS",
)

/**
 * The enriched render model for a Trail history row, or null for a comment row. Swift's
 * `L.historyEnriched(line)` assembles the localized label from these typed pieces — mirroring the Compose
 * `historyLabel()`/`updatedLabel()`.
 */
fun activityHistoryLine(item: ActivityItem): HistoryLine? {
    val row = item as? ActivityItem.HistoryEvent ?: return null
    return when (val event = row.event) {
        is ItemHistoryEvent.StatusChanged -> HistoryLine(
            verb = "STATUS_CHANGED",
            peerTitle = null,
            statusFrom = event.from,
            statusTo = event.to,
            changedFields = emptyList(),
            updatedIsGeneric = false,
        )
        is ItemHistoryEvent.Updated -> {
            val mapped = event.fields.map { UpdatedFieldToken[it] }   // nullable per raw token
            HistoryLine(
                verb = "UPDATED",
                peerTitle = null,
                statusFrom = null,
                statusTo = null,
                changedFields = mapped.filterNotNull().distinct(),
                updatedIsGeneric = event.fields.isEmpty() || mapped.any { it == null },
            )
        }
        else -> HistoryLine(
            verb = historyLineVerb(event),
            peerTitle = row.peerTitle,           // resolved at merge time; null ⇒ "another item"
            statusFrom = null,
            statusTo = null,
            changedFields = emptyList(),
            updatedIsGeneric = false,
        )
    }
}

private fun historyLineVerb(event: ItemHistoryEvent): String = when (event) {
    is ItemHistoryEvent.Created -> "CREATED"
    is ItemHistoryEvent.Updated -> "UPDATED"
    is ItemHistoryEvent.StatusChanged -> "STATUS_CHANGED"
    is ItemHistoryEvent.Moved -> "MOVED"
    is ItemHistoryEvent.Split -> "SPLIT"
    is ItemHistoryEvent.ParentAssigned -> "PARENT_ASSIGNED"
    is ItemHistoryEvent.FoldedInto -> "FOLDED_INTO"
    is ItemHistoryEvent.MergedChild -> "MERGED_CHILD"
    is ItemHistoryEvent.MergedIntoParent -> "MERGED_INTO_PARENT"
    is ItemHistoryEvent.Unknown -> "UNKNOWN"
}

/** True when this history row carries a captured old→new diff (#260) — a tappable ChangeDiffSheet row. */
fun activityHistoryHasDiff(item: ActivityItem): Boolean =
    (item as? ActivityItem.HistoryEvent)?.changes?.isNotEmpty() ?: false

/**
 * The diff rows for the ChangeDiffSheet — the typed twin of `changes.toDiffRows()`. Unknown fields
 * dropped, order preserved. Values stay RAW (Swift formats per [TrailDiffSide]/`fieldToken`).
 */
fun activityHistoryDiffRows(item: ActivityItem): List<TrailDiffRow> {
    val changes = (item as? ActivityItem.HistoryEvent)?.changes ?: return emptyList()
    return changes.mapNotNull { change ->
        val token = diffFieldToken(change.field) ?: return@mapNotNull null   // drops Unknown
        TrailDiffRow(fieldToken = token, before = diffSide(change.before), after = diffSide(change.after))
    }
}

// internal (not private): also reused by ShellBridge's activityRowDiffRows so the Activity feed's
// ChangeDiffSheet maps ActivityFieldChange → TrailDiffRow through the SAME table as the Task Trail.
internal fun diffFieldToken(field: ActivityField): String? = when (field) {
    ActivityField.Title -> "TITLE"
    ActivityField.Description -> "DESCRIPTION"
    ActivityField.Deadline -> "DEADLINE"
    ActivityField.Labels -> "LABELS"
    ActivityField.Status -> "STATUS"
    ActivityField.Pinned -> "PINNED"
    // The #375 peers get their OWN tokens — folding the soft target into DEADLINE would have the Trail
    // report a deadline change for an edit that never touched `complete_by`.
    ActivityField.TargetDate -> "TARGET_DATE"
    ActivityField.Priority -> "PRIORITY"
    ActivityField.Unknown -> null
}

internal fun diffSide(v: ActivityFieldValue): TrailDiffSide = when (v) {
    is ActivityFieldValue.Present -> TrailDiffSide("PRESENT", v.raw)
    ActivityFieldValue.Cleared -> TrailDiffSide("CLEARED", null)
    ActivityFieldValue.Unavailable -> TrailDiffSide("UNAVAILABLE", null)
}

// A trailing issue/PR number on an opaque provider ref (`owner/repo#42` → `42`). A calendar id has no
// trailing `#N`. Regex + names inlined here because the originals live in feature/tasks/ui (no iOS
// target). PREFERRED future cleanup: hoist externalRefLabel/sourceLabel/sourceOriginLabel into core/model
// (Compose-free, iOS-safe — the same move done for journeyStatus/relativeDay) so Compose, desktop, and
// both native bridges share ONE implementation. Out of scope for this port.
private val ExternalRefNumber = Regex("#(\\d+)$")

private fun sourceDisplayName(source: ItemSource): String = when (source) {
    ItemSource.GitHub -> "GitHub"
    ItemSource.GoogleCalendar -> "Google Calendar"
}

/** The dimmed `[GitHub#N]` title prefix for an imported Task, or null (native item / ref with no #N). */
fun taskExternalRefPrefix(task: Task): String? {
    val ext = task.external ?: return null
    val number = ExternalRefNumber.find(ext.id)?.groupValues?.get(1) ?: return null
    return "[${sourceDisplayName(ext.source)}#$number]"
}

/** SOURCE-row origin label: the `owner/repo#N` tracker ref when present, else the provider name. */
fun taskSourceOriginLabel(task: Task): String? {
    val ext = task.external ?: return null
    return if (ext.id.contains('#')) ext.id else sourceDisplayName(ext.source)
}

/** SOURCE-row link (opens in browser), or null when the provenance carries no URL. */
fun taskSourceUrl(task: Task): String? = task.external?.url

/** SOURCE-row provider token for the mark: GITHUB|GOOGLE_CALENDAR, or null when not imported. */
fun taskSourceProviderToken(task: Task): String? = task.external?.let {
    when (it.source) {
        ItemSource.GitHub -> "GITHUB"
        ItemSource.GoogleCalendar -> "GOOGLE_CALENDAR"
    }
}

/**
 * The enriched render model for one Trail history row (ADR-0046) — the typed pieces the Swift
 * `L.historyEnriched` assembles into a localized line, mirroring the Compose `historyLabel`. Only the
 * fields relevant to [verb] are populated. `null` is returned by [activityHistoryLine] for a comment row.
 */
data class HistoryLine(
    val verb: String,                 // CREATED|UPDATED|STATUS_CHANGED|MOVED|SPLIT|PARENT_ASSIGNED|
                                      // FOLDED_INTO|MERGED_CHILD|MERGED_INTO_PARENT|UNKNOWN
    val peerTitle: String?,           // resolved peer title for the peer verbs; null ⇒ Swift falls back to
                                      // L "activity_history_peer_unknown" ("another item")
    val statusFrom: WorkingState?,    // STATUS_CHANGED only — Swift renders via WorkingState.label
    val statusTo: WorkingState?,      // STATUS_CHANGED only
    val changedFields: List<String>,  // UPDATED only — humanizable subset as tokens:
                                      // TITLE|DESCRIPTION|DEADLINE|LABELS (notes→DESCRIPTION,
                                      // complete_by→DEADLINE); order-preserving, de-duped
    val updatedIsGeneric: Boolean,    // UPDATED only — true ⇒ Swift shows the generic "activity_history_updated"
                                      // (empty field list, or any field outside the humanizable subset)
)

/** One old→new field diff row for the ChangeDiffSheet — the typed twin of designsystem `DiffRow`. */
data class TrailDiffRow(
    // TITLE|DESCRIPTION|DEADLINE|LABELS|STATUS|PINNED|TARGET_DATE|PRIORITY (Unknown dropped)
    val fieldToken: String,
    val before: TrailDiffSide,
    val after: TrailDiffSide,
)

/**
 * One side (before/after) of a [TrailDiffRow]. [kind]=PRESENT carries [value]; CLEARED/UNAVAILABLE render
 * a localized word Swift-side. For a PRESENT side the [value] is the RAW model value — Swift does the
 * per-field formatting: DEADLINE and TARGET_DATE = RFC3339 instant (Swift parses+formats), STATUS = wire
 * token (open|in-progress|in-review|done|dropped), PRIORITY = wire bucket (fire|normal|backlog),
 * PINNED = "true"/"false", others verbatim. Mirrors `toDiffValue`/`formatFieldValue` with the formatting
 * moved to Swift (Kotlin/Native has no java.time).
 */
data class TrailDiffSide(
    val kind: String,                 // PRESENT|CLEARED|UNAVAILABLE
    val value: String?,               // PRESENT only; null otherwise
)

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

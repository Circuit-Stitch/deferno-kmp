package com.circuitstitch.deferno.macos.bridge

import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.JourneyLabel
import com.circuitstitch.deferno.core.model.JourneyStyle
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceCursor
import com.circuitstitch.deferno.core.model.RelativeDay
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.journeyStatus
import com.circuitstitch.deferno.core.model.recurrenceCursor
import com.circuitstitch.deferno.core.model.relativeDay
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
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import platform.Foundation.NSData
import platform.Foundation.create
import kotlin.time.Instant
import com.circuitstitch.deferno.feature.tasks.ActivityItem
import com.circuitstitch.deferno.feature.tasks.ParentSummary
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

/** Set the deadline DUE date from a `DatePicker` selection (epoch seconds → the device-zone calendar day). */
fun setTaskDeadline(component: TaskDetailComponent, epochSeconds: Double) {
    val day = Instant.fromEpochMilliseconds((epochSeconds * 1000).toLong())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    component.onSetDeadline(day)
}

/** Clear the deadline DUE date (the explicit clear path). */
fun clearTaskDeadline(component: TaskDetailComponent) = component.onSetDeadline(null)

// --- Combined date+time WHEN picker (#348) — kept IDENTICAL to app/iosApp .../ios/bridge/Bridge.kt. The
// deadline is two axes (CONTRACT-NOTES): the `complete_by` DATE (the server discards its clock) and the
// source-of-truth `deadlineTimeOfDay` CLOCK (null = all-day). The SwiftUI graphical
// `[.date, .hourAndMinute]` picker needs a seed instant that carries a *real* time and a setter that
// dispatches only the changed axis. -------------------------------------------------------------------

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

// --- The soft TARGET DATE (#375) — a PEER of the hard deadline above, not a second deadline. ----------
//
// Two things shape this seam, and both are the opposite of the deadline seam it sits under:
//
//  1. It is **date-granular, full stop.** There is no `targetTimeOfDay` to pair with
//     [Task.deadlineTimeOfDay], so there is deliberately no `applyTargetPicker`, no "add a time" and no
//     all-day/timed three-state here — just a date in and a date out.
//  2. It is **independent** of `completeBy`. Any combination of the two is valid and neither constrains
//     the other, so [clearTaskTargetDate] drops only the soft target. The target date ranks; it never
//     moves the calendar.
//
// The component stores the picked day as that day's *inclusive end* (23:59:59, device zone), which is why
// the seed below re-reads the DAY and hands back its START — feeding the stored instant straight to a
// picker would be the all-day-deadline "11:59 PM" bug (see [taskDeadlinePickerEpochSeconds]) reborn in a
// new field. Round-tripping through the day is also what makes the seed idempotent.

/**
 * The seed instant (epoch seconds, the start of the target day at the device zone) for the **date-only**
 * Target date picker; `-1.0` when the Task carries no soft target — the same out-of-band "unset" sentinel
 * the deadline seed and every `OptionalDatePickerRow` use.
 */
fun taskTargetDatePickerEpochSeconds(task: Task): Double {
    val target = task.targetDate ?: return -1.0
    val zone = TimeZone.currentSystemDefault()
    return target.toLocalDateTime(zone).date.atStartOfDayIn(zone).toEpochMilliseconds() / 1000.0
}

/** Set the soft Target date from a `DatePicker` selection (epoch seconds → the device-zone calendar day). */
fun setTaskTargetDate(component: TaskDetailComponent, epochSeconds: Double) {
    val day = Instant.fromEpochMilliseconds((epochSeconds * 1000).toLong())
        .toLocalDateTime(TimeZone.currentSystemDefault()).date
    component.onSetTargetDate(day)
}

/** Clear the soft Target date. Independent of the hard deadline — this never touches `completeBy`. */
fun clearTaskTargetDate(component: TaskDetailComponent) = component.onSetTargetDate(null)

// PRIORITY (#375) has **no** bridge seam on purpose. `Priority` is a plain `core:model` enum (exported)
// with no value class, no Instant and no clock arithmetic, so SKIE bridges it into a Swift value-type
// enum: the View reads `task.priority` and calls `component.onSetPriority(priority:)` directly, exactly
// as it already does with `WorkingState`. Adding a pass-through here would be a seam that only forwards.

/** Read-only PROPERTIES labels for the Swift view — the opaque-typed fields it can't format itself. */
fun taskTimeLabel(task: Task): String = task.deadlineTimeOfDay?.toString() ?: "—"
fun taskOwnerLabel(task: Task): String = task.ownerOrgId?.value ?: "—"

// ---------------------------------------------------------------------------------------------------
// ATTACHMENTS (#207/#272) — the macOS twin of the iOS bridge's attachment seam. Swift picks files with
// `.fileImporter`/`NSOpenPanel` and hands their bytes across as `NSData`; the reverse codec feeds
// `AVAudioPlayer` for a retained on-device brain-dump recording.
// ---------------------------------------------------------------------------------------------------

/**
 * Upload a file the macOS picker resolved to this Task (#207). Swift can't build an [AttachmentUpload]
 * (its `bytes` is a Kotlin `ByteArray`), so it passes the picked file's [data] as `NSData` and this
 * copies it across — the same `NSData`→`ByteArray` idiom as `feedbackAddAttachment`.
 */
@OptIn(ExperimentalForeignApi::class)
fun addTaskAttachment(component: TaskDetailComponent, filename: String, contentType: String, data: NSData) {
    val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt()) ?: ByteArray(0)
    component.onAddAttachments(listOf(AttachmentUpload(filename = filename, contentType = contentType, bytes = bytes)))
}

/**
 * The reverse of [addTaskAttachment]'s `NSData`→`ByteArray`: copy a Kotlin [ByteArray] into an `NSData`
 * for Swift. `internal` (not `private`) so the sibling `ShellBridge.kt` export bridge can reuse it (#313).
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.toULong()) }

/**
 * Read an **on-device** attachment's bytes for playback (#272) — the retained brain-dump recording the synced
 * `attachments` path never has. The repository read is local + quick, so it runs in a one-shot
 * [Dispatchers.Main] coroutine; [onData] then gets the bytes as `NSData` for `AVAudioPlayer`, or `null` if the
 * row was already deleted. `TaskDetailState.onDeviceAttachments` and
 * `TaskDetailComponent.onDeleteOnDeviceAttachment` are plain enough that Swift reads/calls them directly.
 *
 * This read was empty on macOS until #368 Tranche 5 landed the capture host; it was carried anyway because
 * the SAME seam serves the synced-attachment path the Task-detail sheet always exercised. Both are live now.
 */
fun onDeviceAttachmentData(component: TaskDetailComponent, attachmentId: String, onData: (NSData?) -> Unit) {
    CoroutineScope(Dispatchers.Main).launch {
        onData(component.onDeviceAttachmentBytes(attachmentId)?.toNSData())
    }
}

// ---------------------------------------------------------------------------------------------------
// Connected-parent header + journey-status + relative-day readings (ADR-0044). Kept IDENTICAL to
// app/iosApp .../ios/bridge/Bridge.kt. `JourneyStatus`/`RelativeDay` are pure readings in core/model
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

// ---------------------------------------------------------------------------------------------------
// Recurring-row readings (#384) — the cadence, the end bound and the next-due cursor a recurring [Item]'s
// tree row renders as ONE subtitle line under its title. Kept IDENTICAL to
// app/iosApp .../ios/bridge/Bridge.kt.
//
// `core/model` is exported wholesale, so Swift can already *see* `item.recurrence` — but it cannot take it
// apart. [Cadence]/[RecurrenceBound]/[RecurrenceCursor] are sealed, and a bridged sealed type arrives as an
// opaque class Swift can neither `==` nor pattern-match in a static framework. So these expose the house
// idiom instead — stable String tokens, exactly like [journeyLabelToken]/[taskDueRelativeToken] — and the
// SwiftUI `L.cadence`/`L.cadenceBound`/`L.cursor` map each to its `tasks_cadence_*`/`tasks_recurrence_*`
// catalog key.
//
// **The token is always the discriminator.** The paired count/date accessors return 0 for an arm that
// carries no number, so a `0` is never a value to render — Swift reads one only after a token asked for it.
//
// Nothing here formats a day or names a weekday: Kotlin/Native has no java.time, and CLAUDE.md forbids a
// hand-rolled per-locale table regardless of platform. The wire's English weekday tokens and a bare epoch
// day cross as-is, and Swift resolves both through CLDR (`Calendar.shortWeekdaySymbols`, `DateFormatter`).
// ---------------------------------------------------------------------------------------------------

/**
 * This item's cadence with `EveryNDays(1)` folded into [Cadence.Daily] — the single place that
 * normalisation happens, deliberately *below* the two Swift twins rather than in each of them.
 *
 * "Every 1 day" is what [Cadence.Daily] already says, so `tasks_cadence_every_n_days`' `one` arm is
 * unreachable by design (it exists only so the plural is well-formed, and several locales drop the numeral
 * outright — de does — so the phrase is not even grammatical everywhere). A stride of `0` folds here too:
 * it cannot be rendered as a stride at all, and "Daily" is the least-wrong reading of a rule that fires
 * without one.
 */
private fun renderedCadence(item: Item): Cadence? = when (val cadence = item.recurrence?.cadence) {
    is Cadence.EveryNDays -> if (cadence.n <= 1) Cadence.Daily else cadence
    else -> cadence
}

/**
 * The cadence token: `DAILY | EVERY_N_DAYS | WEEKLY | MONTHLY | YEARLY | CUSTOM | UNMODELLED`, or `null`
 * when the item carries no rule — every [Task], and a recurring definition whose rule did not survive the
 * wire. Swift renders it via `L.cadence` (with [itemCadenceCount]/[itemCadenceWeekdays]).
 *
 * `UNMODELLED` never carries the raw wire token across, and `CUSTOM` never carries the rrule: both are
 * machine text, and the catalog's phrases for them ("Repeats" / "Custom schedule") deliberately promise
 * nothing this build cannot actually read (#382).
 */
fun itemCadenceToken(item: Item): String? = when (renderedCadence(item)) {
    null -> null
    Cadence.Daily -> "DAILY"
    is Cadence.EveryNDays -> "EVERY_N_DAYS"
    is Cadence.Weekly -> "WEEKLY"
    is Cadence.Monthly -> "MONTHLY"
    is Cadence.Yearly -> "YEARLY"
    is Cadence.Custom -> "CUSTOM"
    is Cadence.Unmodelled -> "UNMODELLED"
}

/**
 * The number the cadence plural agrees on: [Cadence.EveryNDays]'s stride, or the `interval` of a
 * [Cadence.Monthly]/[Cadence.Yearly]. 0 for every other arm — they carry no number, and their token says so.
 *
 * An interval of 1 crosses **unchanged**, unlike the `EveryNDays(1)` fold above: `tasks_cadence_monthly` /
 * `tasks_cadence_yearly` put the plain adverb ("Monthly"/"Yearly") in their own `one` arm, so the catalog
 * already does that normalising and doing it here as well would be the same rule written twice. A
 * non-positive interval is floored at 1 for the same reason the stride is: "Every 0 months" is not a
 * sentence, and the adverb is the least-wrong reading of a rule that fires without an interval.
 */
fun itemCadenceCount(item: Item): Int = when (val cadence = renderedCadence(item)) {
    is Cadence.EveryNDays -> cadence.n
    is Cadence.Monthly -> cadence.interval.coerceAtLeast(1)
    is Cadence.Yearly -> cadence.interval.coerceAtLeast(1)
    else -> 0
}

/**
 * The wire's weekday tokens in ISO order — [Cadence.Weekly.days] ships `chrono::Weekday`'s Display form
 * (`"Mon"`..`"Sun"`), which is **English regardless of the user's locale**. This list only turns a token
 * into an index and back; it is never displayed, so it is not the per-locale weekday table CLAUDE.md bans.
 */
private val WireWeekdayTokens = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")

/**
 * The days a [Cadence.Weekly] rule fires on, as the wire's **English** `chrono::Weekday` tokens, never a
 * localized label — Swift maps each through `Calendar.shortWeekdaySymbols`, so the day NAMES come from CLDR
 * on the platform that owns the locale (CLAUDE.md forbids the per-locale table the alternative would need).
 *
 * The list is canonicalised here rather than in either Swift twin, because how it reads is a rendering rule
 * the two must agree on: a token this build cannot place is **dropped** (a rule it cannot fully read still
 * round-trips the cache verbatim, #382 — it must degrade to a shorter list, not take the row down),
 * duplicates collapse, and what survives is sorted into week ORDER. `["Wed", "Mon"]` therefore reads
 * "Mon, Wed": the row states which days a rule fires on, not the order the server happened to serialize
 * them in. The order is ISO (Monday-first, the rule's own week) even where the locale's week starts on
 * Sunday — the four platforms are specified to render this line identically, and Compose sorts the same way.
 *
 * Empty for every other cadence, and for a Weekly rule whose day list did not survive the wire or whose
 * every token was unplaceable — all of which is exactly the day-less "Weekly" reading
 * `tasks_cadence_weekly` exists for.
 */
fun itemCadenceWeekdays(item: Item): List<String> {
    val days = (renderedCadence(item) as? Cadence.Weekly)?.days ?: return emptyList()
    return days
        .mapNotNull { token -> WireWeekdayTokens.firstOrNull { it.equals(token, ignoreCase = true) } }
        .distinct()
        .sortedBy(WireWeekdayTokens::indexOf)
}

// [MonthlyAnchor] — and [Cadence.Yearly]'s month/day — deliberately have NO seam here. "The 2nd Tuesday"
// needs ordinal+weekday grammar that de/es/hi/pt have no existing key family to copy, and the webui does
// not render it on a row either. The row says how OFTEN a thing repeats; #383's detail surface is where
// exactly WHEN belongs. An accessor for it would be a boundary crossing with nothing on the far side.

/**
 * The end-bound token: `ON_DATE | AFTER_COUNT`, or `null` for [RecurrenceBound.Never] — the open-ended
 * default ("the trash goes out every Tuesday", forever), which renders nothing at all rather than a word
 * saying it is unbounded.
 *
 * Note this is the rule's **upper bound**, the peer of the cadence — not [itemCursorToken] below, which is
 * where the series has walked *to*. Reading one as the other is the #384 trap the two names exist to stop.
 */
fun itemBoundToken(item: Item): String? = when (item.recurrence?.bound) {
    null, RecurrenceBound.Never -> null
    is RecurrenceBound.OnDate -> "ON_DATE"
    is RecurrenceBound.AfterCount -> "AFTER_COUNT"
}

/** The firing count of an `AFTER_COUNT` bound (the wire's `COUNT`); 0 for every other bound. */
fun itemBoundCount(item: Item): Int = (item.recurrence?.bound as? RecurrenceBound.AfterCount)?.n ?: 0

/**
 * An `ON_DATE` bound (the wire's `UNTIL`) as **epoch days**; 0 otherwise — the token is the discriminator,
 * so the 1970-01-01 that a bare 0 decodes to is never something Swift reaches.
 *
 * Days, not the epoch *seconds* every picker seam crosses on, because this is a calendar DAY with no clock
 * and no zone: seconds would force Swift to pick a zone to read them in, and every user west of Greenwich
 * would see the day before. Swift reconstructs UTC midnight and formats in UTC, so the day survives intact.
 */
fun itemBoundDateEpochDays(item: Item): Int =
    (item.recurrence?.bound as? RecurrenceBound.OnDate)?.date?.toEpochDays()?.toInt() ?: 0

/**
 * The [RecurrenceCursor] reading: `EXHAUSTED | TODAY | TOMORROW | YESTERDAY | DAYS_AWAY | DAYS_AGO`, or
 * `null` for `NoCursor` — a Task, or an **Archived** definition, whose stale cursor the reading deliberately
 * refuses to believe. Swift renders it via `L.cursor` (with [itemCursorCount]).
 *
 * Derived here on every call rather than carried on the row: [recurrenceCursor] is a reading against
 * *today*, and the Flow behind the Item tree only re-emits when the database changes — a value baked at
 * emit time would still be claiming "Tomorrow" tomorrow.
 *
 * The five relative-day arms are deliberately the SAME tokens [taskDueRelativeToken] emits, so Swift maps
 * them through the one `L.relativeDay` and the `tasks_detail_due_*` keys it already owns. A cursor pointing
 * *backwards* is normal rather than corrupt (a missed Habit's cursor sits where it stopped advancing), so
 * `DAYS_AGO` is an honest reading here, not an error state.
 */
fun itemCursorToken(item: Item): String? = when (val cursor = item.recurrenceCursor()) {
    RecurrenceCursor.NoCursor -> null
    RecurrenceCursor.Exhausted -> "EXHAUSTED"
    is RecurrenceCursor.DueOn -> when (cursor.day) {
        RelativeDay.Today -> "TODAY"
        RelativeDay.Tomorrow -> "TOMORROW"
        RelativeDay.Yesterday -> "YESTERDAY"
        is RelativeDay.DaysAway -> "DAYS_AWAY"
        is RelativeDay.DaysAgo -> "DAYS_AGO"
    }
}

/** The day count for the cursor's `DAYS_AWAY`/`DAYS_AGO` plural (else 0) — feeds `L.cursor(token, count)`. */
fun itemCursorCount(item: Item): Int =
    when (val day = (item.recurrenceCursor() as? RecurrenceCursor.DueOn)?.day) {
        is RelativeDay.DaysAway -> day.days
        is RelativeDay.DaysAgo -> day.days
        else -> 0
    }

// ---------------------------------------------------------------------------------------------------
// ACTIVITY feed (ADR-0043) — the macOS twin of the iOS bridge's comment/activity seam (kept IDENTICAL to
// app/iosApp .../ios/bridge/Bridge.kt). The sealed [ActivityItem] is cracked open with the app's
// manual-discriminator idiom: the View keys ForEach on [activityItemId], unwraps a comment via
// [activityItemComment] (nil ⇒ history), and renders a history row from the enriched [activityHistoryLine]
// + [activityHistoryGlyph] below (ADR-0046). UserId is header-erased (Swift can't ==) and Instant can't be
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

// ---------------------------------------------------------------------------------------------------
// Enriched Trail parity (ADR-0046) — the macOS twin of the iOS bridge's Trail seam. Kept IDENTICAL to
// app/iosApp .../ios/bridge/Bridge.kt, and consumed by macOS TaskDetailView.swift's Trail. All date/time
// formatting stays Swift-side (Kotlin/Native has no java.time) — see `TrailDateFormat`.
// ---------------------------------------------------------------------------------------------------

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
 * token (open|in-progress|in-review|done|dropped), PRIORITY = wire token (fire|normal|backlog), PINNED =
 * "true"/"false", others verbatim. Mirrors `toDiffValue`/`formatFieldValue` with the formatting moved to
 * Swift (Kotlin/Native has no java.time).
 */
data class TrailDiffSide(
    val kind: String,                 // PRESENT|CLEARED|UNAVAILABLE
    val value: String?,               // PRESENT only; null otherwise
)

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

internal fun diffFieldToken(field: ActivityField): String? = when (field) {
    ActivityField.Title -> "TITLE"
    ActivityField.Description -> "DESCRIPTION"
    ActivityField.Deadline -> "DEADLINE"
    ActivityField.Labels -> "LABELS"
    ActivityField.Status -> "STATUS"
    ActivityField.Pinned -> "PINNED"
    // The #375 peers get their OWN tokens — the soft target is never folded into DEADLINE, or the Trail
    // would report a deadline change for an edit that never touched `complete_by`.
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

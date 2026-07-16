package com.circuitstitch.deferno.ios.bridge

import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.JourneyLabel
import com.circuitstitch.deferno.core.model.JourneyStyle
import com.circuitstitch.deferno.core.model.RelativeDay
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.WorkingState
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
    val fieldToken: String,           // TITLE|DESCRIPTION|DEADLINE|LABELS|STATUS|PINNED (Unknown dropped)
    val before: TrailDiffSide,
    val after: TrailDiffSide,
)

/**
 * One side (before/after) of a [TrailDiffRow]. [kind]=PRESENT carries [value]; CLEARED/UNAVAILABLE render
 * a localized word Swift-side. For a PRESENT side the [value] is the RAW model value — Swift does the
 * per-field formatting: DEADLINE = RFC3339 instant (Swift parses+formats), STATUS = wire token
 * (open|in-progress|in-review|done|dropped), PINNED = "true"/"false", others verbatim. Mirrors
 * `toDiffValue`/`formatFieldValue` with the formatting moved to Swift (Kotlin/Native has no java.time).
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

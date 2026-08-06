package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonClassDiscriminator

/**
 * The `/items` polymorphic union (ADR-0011, CONTRACT-NOTES → "Items"). `/items` returns the closed
 * `oneOf{task,habit,chore,event}`, each item flattened and **discriminated by an injected `type`**
 * field (`task`/`habit`/`chore`/`event`); a redundant `kind` duplicates it on `/items` only — we
 * **ignore `kind`** and key off `type`.
 *
 * The mechanism is kotlinx.serialization's [JsonClassDiscriminator] over a sealed type: the
 * discriminator key is `type`, each variant declares its token via [SerialName], and the tolerant
 * reader ([com.circuitstitch.deferno.core.network.DefernoJson], `ignoreUnknownKeys`) lets the
 * redundant `kind` and the unmodelled per-item fields pass through. Sealed-type decoding registers
 * the subtypes automatically — no manual `SerializersModule` needed.
 *
 * v1 only needs a *domain* entity for Task: [Task] maps to `core:model`'s `Task` via
 * `mapper/TaskMapper.kt`; [Habit]/[Chore]/[Event] must parse faithfully but earn their domain
 * entities in their own issues.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@JsonClassDiscriminator("type")
sealed interface ItemView {

    /** The full `task` variant — the same load-bearing fields as [TaskDetailDto]. */
    @Serializable
    @SerialName("task")
    data class Task(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: TaskStatusWire = TaskStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        val children: List<String> = emptyList(),
        @SerialName("complete_by") val completeBy: String? = null,
        @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
        val productive: Double? = null,
        val desire: Double? = null,
        // The soft target date + urgency bucket (#375) — both ride EVERY read, so a cached row can
        // apply the canonical ranked-view key offline. `target_date` is a peer of `complete_by`,
        // NOT a second deadline: it drives sorting/surfacing only and never moves the calendar.
        @SerialName("target_date") val targetDate: String? = null,
        val priority: PriorityWire = PriorityWire.Unknown,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("finished_at") val finishedAt: String? = null,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        @SerialName("next_task_id") val nextTaskId: String? = null,
        // Server-computed subtree progress for a collapsed tree node's badge (ADR-0049, #226). Absent
        // on a freshly-created row → null; `ignoreUnknownKeys` lets older payloads omit them.
        @SerialName("descendant_done") val descendantDone: Long? = null,
        @SerialName("descendant_total") val descendantTotal: Long? = null,
        // Server-derived dependency state (ADR-0034, #289); both booleans default false so a payload
        // omitting them decodes cleanly. `blocked_by` is the ordered edge list on the full record.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
        @SerialName("blocked_by") val blockedBy: List<BlockedByRefDto> = emptyList(),
        // External provenance for a synced/imported item (e.g. a GitHub issue → Task).
        // Absent on a native item; the tolerant reader ignores its unmodelled fields (write_policy/…).
        val external: ExternalProvenanceDto? = null,
        // Backend-hosted attachment metadata, size-only (#311). The cold-sync snapshot carries the full
        // `attachments` array on every item; the client used to drop it. Modelled here as size-only so the
        // DTO→domain mapper can roll it up to `attachment_count` + `attachment_total_size` for offline
        // attachment search/sort (ADR-0042). Absent/empty → no attachments.
        val attachments: List<AttachmentSizeDto> = emptyList(),
        // On-device (brain-dump recording) attachment metadata carried by the offline Backup file (#315,
        // ADR-0041) — distinct from the size-only backend-hosted `attachments` rollup above. The bytes ride
        // the zip at `attachments/<id>`; a real API response never carries this key (defaults empty, the
        // reader ignores it), so it is inert outside a Backup file. Task-only: on-device attachments link to
        // a Task (`local_attachment.task_id`, brain-dump → Task); the other kinds carry no local attachments.
        @SerialName("local_attachments") val localAttachments: List<LocalAttachmentDto> = emptyList(),
        // The two detail-only derived fields of the `ItemDetail` envelope (`GET /items/{id}`, #383).
        // `/items` sends neither — the snapshot collapses chains server-side
        // (`SegmentRetention::DropSuperseded`) and derives no origin label — hence the defaults.
        // DECLARED, NOT ASSUMED: `ignoreUnknownKeys` swallows an unmodelled field silently, so it is
        // invisible to the contract-fixture harness; that is exactly how #381 (`subtask_template`) and
        // #382 (`recurrence.end`) each shipped. `series_chain` can never populate on THIS variant (a
        // Task is backed by no series), but it is declared alongside its three siblings so the union
        // has one shape and the harness sees the key wherever it lands.
        @SerialName("series_chain") val seriesChain: SeriesChainDto? = null,
        // The server-derived Source label — a tracker ref (`owner/repo#N`) or a calendar's display name
        // (#518). It is the `/items/{id}` envelope's alone: `/tasks/{id}` carries the raw `external`
        // block instead and the client derives the label itself (see [TaskDetailDto.external]).
        @SerialName("origin_label") val originLabel: String? = null,
    ) : ItemView

    /** The `habit` variant — a recurring definition with no extra kind-specific fields. */
    @Serializable
    @SerialName("habit")
    data class Habit(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: DefStatusWire = DefStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("complete_by") val completeBy: String? = null,
        @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
        // The soft target date + urgency bucket (#375) — both ride EVERY read, so a cached row can
        // apply the canonical ranked-view key offline. `target_date` is a peer of `complete_by`,
        // NOT a second deadline: it drives sorting/surfacing only and never moves the calendar.
        @SerialName("target_date") val targetDate: String? = null,
        val priority: PriorityWire = PriorityWire.Unknown,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        val recurrence: RecurrenceDto? = null,
        @SerialName("series_id") val seriesId: String? = null,
        // The offline expansion inputs (#410) — see [SeriesInputsDto]. Carried on the SNAPSHOT, not
        // just the detail read, which is what lets an Item-tree row expand its grid with no fetch.
        // `null` is the backend's deliberate ELISION, never an empty grid.
        val series: SeriesInputsDto? = null,
        @SerialName("subtask_template") val subtaskTemplate: List<SubtaskTemplateDto> = emptyList(),
        // Server-derived dependency flags (ADR-0034, #289) — default false when omitted.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
        // The day's firing, inline (#385). Present ONLY on `/items/plan`, where the seeder attaches each
        // recurring row's occurrence beside the flattened definition fields; `/items` never sends it,
        // hence the default. Modelled even though nothing consumes it yet, because an UNMODELLED wire
        // field is invisible to the contract-fixture harness — which is exactly how #381
        // (`subtask_template`) and #382 (`recurrence.end`) each shipped. Its status may be a server
        // *reading* rather than a stored fact (see [OccurrenceDto]); ADR-0053 forbids persisting that,
        // so this is render-only and never reaches a table.
        @SerialName("today_occurrence") val todayOccurrence: OccurrenceDto? = null,
        // The chain of underlying records this item has been split into by its rule changes — see
        // [SeriesChainDto]. Detail-only (`GET /items/{id}`, #383): `/items` collapses chains
        // server-side with `SegmentRetention::DropSuperseded`, hence the default. DECLARED, NOT
        // ASSUMED — `ignoreUnknownKeys` swallows an unmodelled field silently, making it invisible to
        // the contract-fixture harness, which is exactly how #381 (`subtask_template`) and #382
        // (`recurrence.end`) each shipped. Render-only like `today_occurrence`: an era no cold boot
        // could refresh must never reach a table (ADR-0053).
        @SerialName("series_chain") val seriesChain: SeriesChainDto? = null,
        // The server-derived Source label — a tracker ref (`owner/repo#N`) for an imported item, or the
        // calendar's `display_name` for a calendar event (#518). Absent when the calendar cannot be
        // resolved (toggled off / dropped), and the client falls back to the provider label.
        @SerialName("origin_label") val originLabel: String? = null,
    ) : ItemView

    /** The `chore` variant — adds `cadence_mode` over the shared recurring base. */
    @Serializable
    @SerialName("chore")
    data class Chore(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: DefStatusWire = DefStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("complete_by") val completeBy: String? = null,
        @SerialName("deadline_time_of_day") val deadlineTimeOfDay: String? = null,
        // The soft target date + urgency bucket (#375) — both ride EVERY read, so a cached row can
        // apply the canonical ranked-view key offline. `target_date` is a peer of `complete_by`,
        // NOT a second deadline: it drives sorting/surfacing only and never moves the calendar.
        @SerialName("target_date") val targetDate: String? = null,
        val priority: PriorityWire = PriorityWire.Unknown,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        val recurrence: RecurrenceDto? = null,
        @SerialName("series_id") val seriesId: String? = null,
        // The offline expansion inputs (#410) — see [SeriesInputsDto]. Carried on the SNAPSHOT, not
        // just the detail read, which is what lets an Item-tree row expand its grid with no fetch.
        // `null` is the backend's deliberate ELISION, never an empty grid.
        val series: SeriesInputsDto? = null,
        @SerialName("subtask_template") val subtaskTemplate: List<SubtaskTemplateDto> = emptyList(),
        @SerialName("cadence_mode") val cadenceMode: String? = null,
        // Server-derived dependency flags (ADR-0034, #289) — default false when omitted.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
        // The day's firing, inline (#385). Present ONLY on `/items/plan`, where the seeder attaches each
        // recurring row's occurrence beside the flattened definition fields; `/items` never sends it,
        // hence the default. Modelled even though nothing consumes it yet, because an UNMODELLED wire
        // field is invisible to the contract-fixture harness — which is exactly how #381
        // (`subtask_template`) and #382 (`recurrence.end`) each shipped. Its status may be a server
        // *reading* rather than a stored fact (see [OccurrenceDto]); ADR-0053 forbids persisting that,
        // so this is render-only and never reaches a table.
        @SerialName("today_occurrence") val todayOccurrence: OccurrenceDto? = null,
        // The chain of underlying records this item has been split into by its rule changes — see
        // [SeriesChainDto]. Detail-only (`GET /items/{id}`, #383): `/items` collapses chains
        // server-side with `SegmentRetention::DropSuperseded`, hence the default. DECLARED, NOT
        // ASSUMED — `ignoreUnknownKeys` swallows an unmodelled field silently, making it invisible to
        // the contract-fixture harness, which is exactly how #381 (`subtask_template`) and #382
        // (`recurrence.end`) each shipped. Render-only like `today_occurrence`: an era no cold boot
        // could refresh must never reach a table (ADR-0053).
        @SerialName("series_chain") val seriesChain: SeriesChainDto? = null,
        // The server-derived Source label — a tracker ref (`owner/repo#N`) for an imported item, or the
        // calendar's `display_name` for a calendar event (#518). Absent when the calendar cannot be
        // resolved (toggled off / dropped), and the client falls back to the provider label.
        @SerialName("origin_label") val originLabel: String? = null,
    ) : ItemView

    /** The `event` variant — adds `all_day` + `end_time` + start/end time-of-day over the recurring base. */
    @Serializable
    @SerialName("event")
    data class Event(
        val id: String,
        @SerialName("org_slug") val orgSlug: String,
        @SerialName("owner_org_id") val ownerOrgId: String? = null,
        val ref: String? = null,
        val sequence: Long? = null,
        val title: String,
        val status: DefStatusWire = DefStatusWire.Unknown,
        val labels: List<String> = emptyList(),
        @SerialName("parent_id") val parentId: String? = null,
        @SerialName("complete_by") val completeBy: String? = null,
        // The soft target date + urgency bucket (#375) — both ride EVERY read, so a cached row can
        // apply the canonical ranked-view key offline. `target_date` is a peer of `complete_by`,
        // NOT a second deadline: it drives sorting/surfacing only and never moves the calendar.
        @SerialName("target_date") val targetDate: String? = null,
        val priority: PriorityWire = PriorityWire.Unknown,
        val pinned: Boolean = false,
        @SerialName("date_created") val dateCreated: String,
        @SerialName("deleted_at") val deletedAt: String? = null,
        val description: String? = null,
        val recurrence: RecurrenceDto? = null,
        @SerialName("series_id") val seriesId: String? = null,
        // The offline expansion inputs (#410) — see [SeriesInputsDto]. Carried on the SNAPSHOT, not
        // just the detail read, which is what lets an Item-tree row expand its grid with no fetch.
        // `null` is the backend's deliberate ELISION, never an empty grid.
        val series: SeriesInputsDto? = null,
        @SerialName("subtask_template") val subtaskTemplate: List<SubtaskTemplateDto> = emptyList(),
        @SerialName("all_day") val allDay: Boolean = false,
        @SerialName("end_time") val endTime: String? = null,
        @SerialName("start_time_of_day") val startTimeOfDay: String? = null,
        @SerialName("end_time_of_day") val endTimeOfDay: String? = null,
        // Server-derived dependency flags (ADR-0034, #289) — default false when omitted.
        val blocked: Boolean = false,
        @SerialName("is_blocker") val isBlocker: Boolean = false,
        // The day's firing, inline (#385). Present ONLY on `/items/plan`, where the seeder attaches each
        // recurring row's occurrence beside the flattened definition fields; `/items` never sends it,
        // hence the default. Modelled even though nothing consumes it yet, because an UNMODELLED wire
        // field is invisible to the contract-fixture harness — which is exactly how #381
        // (`subtask_template`) and #382 (`recurrence.end`) each shipped. Its status may be a server
        // *reading* rather than a stored fact (see [OccurrenceDto]); ADR-0053 forbids persisting that,
        // so this is render-only and never reaches a table.
        @SerialName("today_occurrence") val todayOccurrence: OccurrenceDto? = null,
        // The chain of underlying records this item has been split into by its rule changes — see
        // [SeriesChainDto]. Detail-only (`GET /items/{id}`, #383): `/items` collapses chains
        // server-side with `SegmentRetention::DropSuperseded`, hence the default. DECLARED, NOT
        // ASSUMED — `ignoreUnknownKeys` swallows an unmodelled field silently, making it invisible to
        // the contract-fixture harness, which is exactly how #381 (`subtask_template`) and #382
        // (`recurrence.end`) each shipped. Render-only like `today_occurrence`: an era no cold boot
        // could refresh must never reach a table (ADR-0053).
        @SerialName("series_chain") val seriesChain: SeriesChainDto? = null,
        // The server-derived Source label — a tracker ref (`owner/repo#N`) for an imported item, or the
        // calendar's `display_name` for a calendar event (#518). Absent when the calendar cannot be
        // resolved (toggled off / dropped), and the client falls back to the provider label.
        @SerialName("origin_label") val originLabel: String? = null,
    ) : ItemView
}

/**
 * One edge of a Task's wire `blocked_by` array (ADR-0034, #289) → domain
 * [com.circuitstitch.deferno.core.model.BlockedByRef]. [item] is the blocker's id; [occurrence] is an
 * optional dated-firing ref — a later follow-up, so it is decoded tolerantly and defaults `null`.
 */
@Serializable
data class BlockedByRefDto(
    val item: String,
    val occurrence: String? = null,
)

/**
 * The wire `external` provenance block carried on a synced/imported item (the `ExternalProvenance` schema):
 * the opaque provider [id] (`owner/repo#N` for a GitHub issue), the short [source] key (`github`,
 * `google_calendar`), and the optional provider-side [url]. The wire also carries `write_policy`,
 * `updated_at`, and `master_id`; the client doesn't display them, so the tolerant reader drops them. Maps
 * to the domain [com.circuitstitch.deferno.core.model.ExternalRef] in `TaskMapper.kt`.
 */
@Serializable
data class ExternalProvenanceDto(
    val id: String,
    val source: String,
    val url: String? = null,
)

/**
 * One entry of a recurring definition's wire `subtask_template` array (`SubtaskTemplate`, backend
 * `subtask_template.rs`) — the template a Habit/Chore/Event clones into fresh subtask Tasks each time an
 * occurrence materializes. The wire element is an **object**, never a bare string (#381): modelling it as
 * `List<String>` made the first populated template throw a `SerializationException` inside the `/items`
 * decode, which `requestApi` maps to [com.circuitstitch.deferno.core.network.ApiError.Transport] →
 * `RemoteSnapshot.Unavailable` → an early-returning `ItemSync.refresh()` — a silent cold-sync stall for
 * **all four kinds, Tasks included**.
 *
 * [description] **must** default to `""`: the backend declares it
 * `#[serde(default, skip_serializing_if = "String::is_empty")]`, so the key is absent both when the
 * description is empty and on every legacy row. Read-only for now — nothing on the client writes
 * templates yet — but faithful, so a round-trip through the Backup file (ADR-0041) preserves them.
 */
@Serializable
data class SubtaskTemplateDto(
    val id: String,
    val title: String,
    val description: String = "",
)

/**
 * The recurrence rule carried by Habit/Chore/Event (CONTRACT-NOTES → "Items"), and by the create /
 * convert payloads on the way out.
 *
 * **The wire is FLAT.** The backend hand-writes `Serialize`/`Deserialize` for `Recurrence`
 * (`backend/src/models/recurrence.rs`) because `#[serde(flatten)]` does not round-trip over an
 * internally-tagged enum: the cadence's own fields are hoisted to the top level next to `type`, and
 * only the optional bound is nested under `end`. All six cadences, verbatim:
 *
 * ```
 * {"type":"daily"}
 * {"type":"every_n_days","n":3}
 * {"type":"weekly","days":["Mon","Wed"]}
 * {"type":"monthly","interval":1,"on":{"type":"day_of_month","day":15}}
 * {"type":"monthly","interval":2,"on":{"type":"nth_weekday","nth":-1,"weekday":"Fri"}}
 * {"type":"yearly","interval":1,"month":6,"day":14}
 * {"type":"custom","rrule":"FREQ=WEEKLY;BYDAY=MO,WE"}
 * ```
 *
 * …plus an optional `"end": {…}`. **Do not model this from `contracts/openapi-0.1.json`** — utoipa
 * derived that schema from the Rust struct's fields and never saw the hand-written impl, so it
 * advertises a nested `cadence` object with a required `end`, a shape the server never emits. See
 * CONTRACT-NOTES → "Recurrence".
 *
 * **Flat and all-defaulted, deliberately — never a kotlinx sealed hierarchy.**
 * [com.circuitstitch.deferno.core.network.DefernoJson] registers no `SerializersModule` polymorphic
 * default, so an unknown future discriminator would *throw* inside the very same `/items` decode and
 * reintroduce the #381 cold-sync stall this file just fixed. A field that does not apply to the
 * received cadence is simply absent → null, which is also what keeps every zero-arg/`type`-only
 * construction site source-compatible.
 */
@Serializable
data class RecurrenceDto(
    /** `daily` / `every_n_days` / `weekly` / `monthly` / `yearly` / `custom`. */
    val type: String? = null,
    /** `weekly` — `"Mon"`..`"Sun"` (`chrono::Weekday`'s Display form). */
    val days: List<String> = emptyList(),
    /** `every_n_days` — the day interval. */
    val n: Int? = null,
    /** `monthly` / `yearly` — the cycle (1 = every month/year, 2 = every other, …). */
    val interval: Int? = null,
    /** `monthly` — which day within the cycle. */
    val on: MonthlyAnchorDto? = null,
    /** `yearly` — the month, `1..12`. */
    val month: Int? = null,
    /** `yearly` — the day of that month, `1..31`. */
    val day: Int? = null,
    /** `custom` — the raw RFC-5545 rule; it owns its own bound, so `end` is meaningless beside it. */
    val rrule: String? = null,
    /** The optional upper bound. **Absent is the only encoding of "never" the server emits.** */
    val end: RecurrenceEndDto? = null,
)

/**
 * The nested `recurrence.on` anchor for a `monthly` cadence (`MonthlyAnchor`). Flat + tolerant for the
 * same reason as [RecurrenceDto]: [type] is `day_of_month` (carrying [day]) or `nth_weekday` (carrying
 * [nth] + [weekday]). [nth] is an `i8` on the wire — `1..5`, or **`-1` meaning "last"**.
 */
@Serializable
data class MonthlyAnchorDto(
    val type: String? = null,
    val day: Int? = null,
    val nth: Int? = null,
    val weekday: String? = null,
)

/**
 * The nested `recurrence.end` bound (`RecurrenceEnd`). [type] is `never` / `on_date` (carrying an
 * ISO-8601 [date]) / `after_count` (carrying [n]).
 *
 * The server **never emits `{"type":"never"}`** — its `Serialize` skips the whole `end` key when the
 * bound is never, so an absent `end` IS the never bound. Its `Deserialize` does accept the explicit
 * form, so the reader tolerates it too.
 */
@Serializable
data class RecurrenceEndDto(
    val type: String? = null,
    val date: String? = null,
    val n: Int? = null,
)

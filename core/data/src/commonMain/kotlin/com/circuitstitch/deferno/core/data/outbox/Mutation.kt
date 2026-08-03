package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.network.mapper.OccurrenceKind
import com.circuitstitch.deferno.core.network.mapper.toWireToken
import com.circuitstitch.deferno.core.network.mapper.toWorkingState
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlin.time.Instant

/**
 * The offline write-path intent set (ADR-0001, ADR-0011, #23): the deliverable
 * **intent → endpoint → minimal-body table**, modelled as a sealed type rather than a generic
 * "update DTO". A `Mutation` is transient — it exists only long enough to (a) apply optimistically to
 * the local cache and (b) produce the [OutboxRequest] the outbox persists and replays.
 *
 * **Intent-based, not patch-from-X-to-Y (ADR-0001 LWW).** Each intent names *what changed*
 * (`SetWorkingState(Done)`), not a diff, so replaying it is idempotent and two intents over
 * independent fields (status vs title) never clobber each other.
 *
 * **Minimal, never-absent bodies (ADR-0011).** [toRequest] builds a `JsonObject` carrying *only* the
 * keys the intent changes and renders it to a string sent verbatim. A nullable field's intent emits
 * an explicit `null` to mean **"clear it"** (`ClearDeadline` → `{"complete_by":null}`); a set emits
 * the value; **no intent ever emits an absent field**, so a missing value can never clobber a server
 * field. (The omit-only `oneOf` `status` is only ever *set*, never cleared.)
 *
 * The table:
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [SetWorkingState] | `PATCH tasks/{id}` | `{"status":"<open\|in-progress\|in-review\|done\|dropped>"}` |
 * | [Rename] | `PATCH tasks/{id}` | `{"title":"…"}` |
 * | [SetDeadline] | `PATCH tasks/{id}` | `{"complete_by":"<rfc3339>"}` |
 * | [ClearDeadline] | `PATCH tasks/{id}` | `{"complete_by":null}` |
 * | [SetDeadlineTime] | `PATCH tasks/{id}` | `{"deadline_time_of_day":"HH:MM"}` (a `null` = all-day) |
 * | [SetDescription] | `PATCH tasks/{id}` | `{"description":"…"}` |
 * | [ClearDescription] | `PATCH tasks/{id}` | `{"description":null}` |
 * | [SetLabels] | `PATCH tasks/{id}` | `{"labels":[…]}` |
 * | [SetPinned] | `PATCH tasks/{id}` | `{"pinned":<bool>}` |
 * | [SetTargetDate] | `PATCH tasks/{id}` | `{"target_date":"<rfc3339>"}` (a `null` clears the soft date) |
 * | [SetPriority] | `PATCH tasks/{id}` | `{"priority":"<fire\|normal\|backlog>"}` (never `null`) |
 * | [DeleteTask] | `DELETE tasks/{id}` | *(no body; soft-delete)* |
 * | [PlanAdd] | `POST tasks/plan/add` | `{"task_id":"…","date":"…","tz":"…"}` |
 * | [PlanRemove] | `POST tasks/plan/remove` | `{"task_id":"…","date":"…","tz":"…"}` |
 * | [PlanReorder] | `POST tasks/plan/reorder` | `{"task_ids":[…],"date":"…","tz":"…"}` |
 *
 * **Create now rides the outbox too (#185).** With the backend accepting client-supplied ids
 * (Circuit-Stitch/Deferno#402), an offline create mints the Item UUID up front and enqueues a
 * [CreateMutation] (`POST /{kind}` with that id) — the id is the idempotency key, so a replay can't
 * duplicate. A create *inserts* rather than transforms an existing row, so [CreateMutation] carries no
 * `applyTo` (the writer does the optimistic insert directly) and is routed specially on replay (it
 * needs the server's returned id to confirm / heal). Every intent in *this* file still mutates an
 * **existing** entity (stable UUID), which is what makes its replay reconcile-clean.
 */
sealed interface Mutation {

    /**
     * A coarse partition key for the entity this intent targets — `task:{id}` or `plan:{date}:{tz}`.
     * Stored on the outbox row for diagnostics/observability; the FIFO replay order is the global
     * enqueue sequence (not partitioned by target) so strict ordering is preserved across entities.
     */
    val target: String

    /** The minimal, idempotent wire request this intent replays (the table above). */
    fun toRequest(): OutboxRequest
}

/**
 * A [Mutation] against a single existing [Task]. Carries the pure optimistic transform [applyTo] the
 * writer applies to the cached row the instant the user acts (ADR-0001 optimistic apply) — before the
 * request ever reaches the server.
 */
sealed interface TaskMutation : Mutation {
    val taskId: TaskId
    override val target: String get() = "task:${taskId.value}"

    /**
     * The optimistic local effect — a **pure** transform of the cached [task] (no side effects, no
     * exceptions). It must be replay-safe: `applyTo(applyTo(t)) == applyTo(t)`, mirroring the
     * idempotence of the wire intent, so a double-apply (e.g. a re-enqueue) never compounds.
     */
    fun applyTo(task: Task): Task
}

/**
 * A [Mutation] against one day's plan *ordering* for `(date, tz)`. The plan store holds only the
 * ordered ids (#22); [applyTo] is the pure transform of that order the writer applies optimistically.
 */
sealed interface PlanMutation : Mutation {
    val date: LocalDate
    val tz: String
    override val target: String get() = "plan:$date:$tz"

    /** The optimistic local effect on the day's ordered ids — **pure** and idempotent. */
    fun applyTo(order: List<TaskId>): List<TaskId>
}

/**
 * A [Mutation] against the user's single [UserSettings] bag (`PATCH /auth/me/settings`, #72). Each
 * intent names *what changed* (`SetTheme(Mono, Dark)`, `SetTracking(false)`) — not a diff — so a
 * replay is idempotent and two intents over independent fields never clobber each other (ADR-0001
 * LWW). [applyTo] is the pure optimistic transform the writer applies to the cached settings the
 * instant the user acts, so Appearance changes apply live before the request reaches the server.
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [SetTheme] | `PATCH auth/me/settings` | `{"theme_family":"…","theme_mode":"…"}` |
 * | [SetTracking] | `PATCH auth/me/settings` | `{"tracking_enabled":<bool>}` |
 * | [SetDragAndDrop] | `PATCH auth/me/settings` | `{"drag_and_drop_enabled":<bool>}` |
 * | [SetDoneVisibility] | `PATCH auth/me/settings` | `{"global_done_visibility_seconds":…,"dashboard_done_visibility_seconds":…}` |
 */
sealed interface SettingsMutation : Mutation {
    override val target: String get() = TARGET

    /** The optimistic local effect on the cached settings — **pure** and idempotent. */
    fun applyTo(settings: UserSettings): UserSettings

    companion object {
        /**
         * The one settings [target] (the bag is a singleton row). The settings reconcile checks the
         * outbox for this target so a refresh can't clobber an un-synced optimistic change (#143).
         */
        const val TARGET: String = "settings"
    }
}

// --- Task intents ---

/** Set a Task's [WorkingState] (`open`/`in-progress`/`in-review`/`done`/`dropped`). */
data class SetWorkingState(override val taskId: TaskId, val state: WorkingState) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(workingState = state)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("status", state.toWireToken()) }
}

/** Rename a Task. */
data class Rename(override val taskId: TaskId, val title: String) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(title = title)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("title", title) }
}

/** Set a Task's deadline. */
data class SetDeadline(override val taskId: TaskId, val completeBy: Instant) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(completeBy = completeBy)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("complete_by", completeBy.toString()) }
}

/** Clear a Task's deadline — `null` means "clear it" (ADR-0011), distinct from omit. */
data class ClearDeadline(override val taskId: TaskId) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(completeBy = null)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("complete_by", JsonNull) }
}

/**
 * Set (or clear) a Task's deadline **clock time** (#348) — the source-of-truth time axis, separate from
 * the `complete_by` date axis (CONTRACT-NOTES: the server discards `complete_by`'s clock and lets
 * `deadline_time_of_day` win). [timeOfDay] `null` = **all-day** (an explicit clear, distinct from omit —
 * ADR-0011). Sent as an `"HH:MM"` string (the server reads it back leniently as `"HH:MM:SS"`).
 */
data class SetDeadlineTime(override val taskId: TaskId, val timeOfDay: LocalTime?) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(deadlineTimeOfDay = timeOfDay)
    override fun toRequest(): OutboxRequest = patchTask(taskId) {
        if (timeOfDay == null) put("deadline_time_of_day", JsonNull) else put("deadline_time_of_day", timeOfDay.toString())
    }
}

/** Set a Task's description body. */
data class SetDescription(override val taskId: TaskId, val description: String) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(description = description)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("description", description) }
}

/** Clear a Task's description — explicit `null` = "clear it". */
data class ClearDescription(override val taskId: TaskId) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(description = null)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("description", JsonNull) }
}

/** Replace a Task's labels. (An empty list clears them; the field is always present, never absent.) */
data class SetLabels(override val taskId: TaskId, val labels: List<String>) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(labels = labels)
    override fun toRequest(): OutboxRequest = patchTask(taskId) {
        putJsonArray("labels") { labels.forEach { add(it) } }
    }
}

/** Pin or unpin a Task. */
data class SetPinned(override val taskId: TaskId, val pinned: Boolean) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(pinned = pinned)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("pinned", pinned) }
}

/**
 * Set (or clear) a Task's **soft target date** (#375) — when the person *wants* it done by, a peer of
 * the hard `complete_by` and deliberately independent of it. Emits `target_date` **alone**, so wanting
 * something sooner can never move the real deadline.
 *
 * One intent with a nullable operand rather than a Set/Clear pair (the [SetDeadlineTime] shape, not the
 * older [SetDeadline]/[ClearDeadline] split): it maps exactly onto the server's `Patch<DateTime<Utc>>`
 * — a value sets, an explicit `null` clears, an omitted key leaves unchanged. Since an omitted key is a
 * silent no-op server-side, the clear MUST emit an explicit `null` (ADR-0011).
 */
data class SetTargetDate(override val taskId: TaskId, val targetDate: Instant?) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(targetDate = targetDate)
    override fun toRequest(): OutboxRequest = patchTask(taskId) {
        if (targetDate == null) put("target_date", JsonNull) else put("target_date", targetDate.toString())
    }
}

/**
 * Set a Task's urgency bucket (#375) — `fire`/`normal`/`backlog` via [Priority.toWireToken].
 *
 * **Deliberately not nullable.** The server types this `Option<Priority>`, where omit means "leave
 * unchanged" and there is *no* null form: `priority` is never absent on a row, it defaults to `Normal`.
 * So "clearing" it is spelled `SetPriority(Normal)`, and an explicit `null` would be a 422 — Terminal —
 * which the outbox would dead-letter rather than merely retry. This is the one nullable-looking field on
 * the PATCH surface that must never emit [JsonNull].
 */
data class SetPriority(override val taskId: TaskId, val priority: Priority) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(priority = priority)
    override fun toRequest(): OutboxRequest = patchTask(taskId) { put("priority", priority.toWireToken()) }
}

/**
 * Soft-delete a Task (`DELETE tasks/{id}`, no body — the server tombstones it). The optimistic effect
 * is a local tombstone at [deletedAt] (the writer passes its `now`), so the row drops out of the
 * active list immediately; the post-flush reconcile then converges on the server's tombstone (or its
 * absence from the snapshot — either way the row stays deleted locally, ADR-0001 LWW).
 */
data class DeleteTask(override val taskId: TaskId, val deletedAt: Instant) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(deletedAt = deletedAt)

    // No `activity` stamp (#364): the backend's soft-delete migration covered comments, attachments and
    // occurrence-clears but NOT item delete, so this stays a bodiless `DELETE` with no entity to merge
    // one into. The server mints that entry id and the optimistic row is superseded, not merged.
    override fun toRequest(): OutboxRequest = OutboxRequest(OutboxMethod.Delete, listOf("tasks", taskId.value))
}

/**
 * The **old** values of exactly the fields this Task edit changes, in the same JSON keys/encoding as
 * [toRequest]'s body — the "before" half of the Activity ledger's old->new diff (#260 follow-up),
 * snapshotted from the pre-apply cached [task]. `null` for [DeleteTask] (a delete has no field diff).
 *
 * Symmetry with the new-value body is the point: every key here also appears in `toRequest().body`, so a
 * reader zips the two objects per key. `description` is a hydrate-on-demand field ([HydrationState.Full]
 * only) — on a summary row the cached value is null even when the server holds text, so an un-hydrated
 * description edit **omits** the key (the reader renders "previously unavailable") rather than falsely
 * claiming the old body was empty.
 */
internal fun TaskMutation.beforeValues(task: Task): JsonObject? = when (this) {
    is SetWorkingState -> buildJsonObject { put("status", task.workingState.toWireToken()) }
    is Rename -> buildJsonObject { put("title", task.title) }
    is SetDeadline, is ClearDeadline -> buildJsonObject {
        val by = task.completeBy
        if (by == null) put("complete_by", JsonNull) else put("complete_by", by.toString())
    }
    is SetDeadlineTime -> buildJsonObject {
        val at = task.deadlineTimeOfDay
        if (at == null) put("deadline_time_of_day", JsonNull) else put("deadline_time_of_day", at.toString())
    }
    is SetDescription, is ClearDescription -> buildJsonObject {
        if (task.hydration == HydrationState.Full) {
            val desc = task.description
            if (desc == null) put("description", JsonNull) else put("description", desc)
        }
        // else: omit "description" — the old body is unknown on an un-hydrated (Summary) row.
    }
    is SetLabels -> buildJsonObject { putJsonArray("labels") { task.labels.forEach { add(it) } } }
    is SetPinned -> buildJsonObject { put("pinned", task.pinned) }
    is SetTargetDate -> buildJsonObject {
        val want = task.targetDate
        if (want == null) put("target_date", JsonNull) else put("target_date", want.toString())
    }
    // The old bucket is always a real value (never absent — it defaults to Normal), so unlike the
    // nullable fields above this arm has no null branch to consider.
    is SetPriority -> buildJsonObject { put("priority", task.priority.toWireToken()) }
    is DeleteTask -> null
}

// --- Plan intents ---

/** Add a Task to a day's plan (idempotent: a no-op locally if already present). */
data class PlanAdd(val taskId: TaskId, override val date: LocalDate, override val tz: String) : PlanMutation {
    override fun applyTo(order: List<TaskId>): List<TaskId> = if (taskId in order) order else order + taskId
    override fun toRequest(): OutboxRequest = postPlan("add") {
        put("task_id", taskId.value); put("date", date.toString()); put("tz", tz)
    }
}

/** Remove a Task from a day's plan (idempotent: a no-op locally if already absent). */
data class PlanRemove(val taskId: TaskId, override val date: LocalDate, override val tz: String) : PlanMutation {
    override fun applyTo(order: List<TaskId>): List<TaskId> = order - taskId
    override fun toRequest(): OutboxRequest = postPlan("remove") {
        put("task_id", taskId.value); put("date", date.toString()); put("tz", tz)
    }
}

/** Set a day's plan to an exact order (idempotent: replays to the same order). */
data class PlanReorder(val taskIds: List<TaskId>, override val date: LocalDate, override val tz: String) : PlanMutation {
    override fun applyTo(order: List<TaskId>): List<TaskId> = taskIds
    override fun toRequest(): OutboxRequest = postPlan("reorder") {
        putJsonArray("task_ids") { taskIds.forEach { add(it.value) } }
        put("date", date.toString()); put("tz", tz)
    }
}

// --- Item intents (cross-kind tree move, ADR-0049 #228) ---

/**
 * Reparent + reorder one Item (`POST items/{id}/move`, ADR-0049 decision 5, #228). Unlike a
 * [TaskMutation] this is a **cross-kind** intent — the moved Item and its target siblings may be
 * different kinds — so it carries no single-[Task] `applyTo`; the optimistic reorder spans the four
 * per-kind stores and lives in [com.circuitstitch.deferno.core.data.item.OutboxItemWriter].
 *
 * [newParentId] is the destination parent — an explicit wire `null` **detaches to root** (distinct from
 * omit, ADR-0011). [position] is the insertion index among the destination's children; the server
 * reassigns `sequence`. A server **400** (cycle) is a terminal rejection the next cold-snapshot reconcile
 * corrects (LWW) — the client greys out illegal targets, so it is only ever a rare race.
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [Move] | `POST items/{id}/move` | `{"new_parent_id":"…"\|null,"position":<int>}` |
 */
data class Move(val id: String, val newParentId: String?, val position: Int) : Mutation {
    override val target: String get() = "item:$id"
    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Post,
        listOf("items", id, "move"),
        buildJsonObject {
            if (newParentId == null) put("new_parent_id", JsonNull) else put("new_parent_id", newParentId)
            put("position", position)
        }.toString(),
        acceptsActivityStamp = true,
    )
}

/**
 * Set a recurring **definition's** [DefinitionState] — the Habit/Chore/Event "light switch" (#299), the
 * recurring-kind sibling of [SetWorkingState]. Cross-kind like [Move] (it addresses the **raw Item id
 * string**, not a kind-typed id), so it carries no single-`Task` `applyTo`; instead it exposes a typed
 * optimistic transform per kind ([applyTo] overloads), and [OutboxDefinitionWriter] dispatches to the
 * right per-kind store. [kind] selects the kind-scoped endpoint + the wire token round-trips with the read
 * mapper (`DefStatusWire`). Offline-first (ADR-0001): optimistic apply + enqueue, like the Task edits.
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [SetDefinitionState] habit | `PATCH habits/{id}` | `{"status":"<active\|in-review\|archived>"}` |
 * | [SetDefinitionState] chore | `PATCH chores/{id}` | `{"status":"<active\|in-review\|archived>"}` |
 * | [SetDefinitionState] event | `PATCH events/{id}` | `{"status":"<active\|in-review\|archived>"}` |
 */
data class SetDefinitionState(val id: String, val kind: ItemKind, val state: DefinitionState) : Mutation {
    override val target: String get() = "item:$id"

    /** The optimistic local effect on a cached Habit/Chore/Event — **pure** and idempotent (replay-safe). */
    fun applyTo(habit: Habit): Habit = habit.copy(definitionState = state)
    fun applyTo(chore: Chore): Chore = chore.copy(definitionState = state)
    fun applyTo(event: Event): Event = event.copy(definitionState = state)

    override fun toRequest(): OutboxRequest = patchRecurring(kind, id) { put("status", state.toWireToken()) }
}

// --- Settings intents ---

/** Set the appearance: theme family + mode (Appearance category, #72). Applied live + persisted. */
data class SetTheme(val family: ThemeFamily, val mode: ThemeMode) : SettingsMutation {
    override fun applyTo(settings: UserSettings): UserSettings =
        settings.copy(themeFamily = family, themeMode = mode)

    override fun toRequest(): OutboxRequest = patchSettings {
        put("theme_family", family.toWireToken())
        put("theme_mode", mode.toWireToken())
    }
}

/** Toggle analytics/tracking (Data & Privacy category, #72). */
data class SetTracking(val enabled: Boolean) : SettingsMutation {
    override fun applyTo(settings: UserSettings): UserSettings = settings.copy(trackingEnabled = enabled)
    override fun toRequest(): OutboxRequest = patchSettings { put("tracking_enabled", enabled) }
}

/** Toggle the experimental drag-and-drop affordance (Task behavior category, #72). */
data class SetDragAndDrop(val enabled: Boolean) : SettingsMutation {
    override fun applyTo(settings: UserSettings): UserSettings = settings.copy(dragAndDropEnabled = enabled)
    override fun toRequest(): OutboxRequest = patchSettings { put("drag_and_drop_enabled", enabled) }
}

/**
 * Set the done-visibility windows (Task behavior category, #72): how long completed items stay
 * visible in the global list and on the dashboard. A `null` means "clear it" — an explicit wire
 * `null`, distinct from omit (ADR-0011).
 */
data class SetDoneVisibility(
    val globalSeconds: Long?,
    val dashboardSeconds: Long?,
) : SettingsMutation {
    override fun applyTo(settings: UserSettings): UserSettings = settings.copy(
        globalDoneVisibilitySeconds = globalSeconds,
        dashboardDoneVisibilitySeconds = dashboardSeconds,
    )

    override fun toRequest(): OutboxRequest = patchSettings {
        if (globalSeconds == null) put("global_done_visibility_seconds", JsonNull) else put("global_done_visibility_seconds", globalSeconds)
        if (dashboardSeconds == null) put("dashboard_done_visibility_seconds", JsonNull) else put("dashboard_done_visibility_seconds", dashboardSeconds)
    }
}

// --- minimal-body builders (the "never emit an absent field" rule lives here) ---

/** A `PATCH tasks/{id}` whose body is exactly the keys [build] sets — nothing absent (ADR-0011). */
private fun patchTask(id: TaskId, build: JsonObjectBuilder.() -> Unit): OutboxRequest =
    OutboxRequest(
        OutboxMethod.Patch,
        listOf("tasks", id.value),
        buildJsonObject(build).toString(),
        acceptsActivityStamp = true,
    )

/** A `POST tasks/plan/{action}` whose body is exactly the keys [build] sets. */
private fun postPlan(action: String, build: JsonObjectBuilder.() -> Unit): OutboxRequest =
    OutboxRequest(
        OutboxMethod.Post,
        listOf("tasks", "plan", action),
        buildJsonObject(build).toString(),
        acceptsActivityStamp = true,
    )

/**
 * A `PATCH auth/me/settings` whose body is exactly the keys [build] sets — nothing absent (ADR-0011).
 *
 * The one builder here that does **not** opt into the `activity` stamp (#364): a user-preferences write
 * against a strict payload, not an Item mutation, so an unexpected key would `422` — Terminal — and the
 * outbox would dead-letter the user's theme/tracking change rather than merely fail to audit it.
 */
private fun patchSettings(build: JsonObjectBuilder.() -> Unit): OutboxRequest =
    OutboxRequest(OutboxMethod.Patch, listOf("auth", "me", "settings"), buildJsonObject(build).toString())

/**
 * A `PATCH {kind}/{id}` against a recurring **definition** (#299) whose body is exactly the keys [build]
 * sets — the recurring-kind mirror of [patchTask]. [kind] selects the kind-scoped prefix
 * (`habits`/`chores`/`events`); a `Task` is rejected (it has no definition state).
 */
private fun patchRecurring(kind: ItemKind, id: String, build: JsonObjectBuilder.() -> Unit): OutboxRequest =
    OutboxRequest(
        OutboxMethod.Patch,
        listOf(kind.recurringPath(), id),
        buildJsonObject(build).toString(),
        acceptsActivityStamp = true,
    )

/**
 * A [Mutation] against one dated firing (an Occurrence) of a recurring definition (#74) — the
 * firing-level sibling of `TaskMutation`. It is the write half of the Calendar surface, deferred at
 * envelope v0.1 (see the class note above) until #71 supplied the firing domain + cache. Unlike a Task
 * intent it targets a `(kind, definitionId, date)` firing, because the occurrence endpoints are
 * kind-scoped (`/habits|chores|events/{definitionId}/occurrences/…`). Offline-first (ADR-0001): these
 * target an **existing** server entity, so they ride the normal outbox — *not* online-only like create.
 *
 * **The `{id}` path segment is the ITEM id, never the series id (#380).** Every occurrence handler
 * resolves it through `load_owned_{habit,chore,event}` → `load_item_for_user`, so it wants the *item*
 * the firing projects from — the chain Head, which the calendar feed emits as `task_id` and
 * [CalendarItem.taskId] already carries. The addressed item is only an entry point: the server walks
 * the chain and resolves the owning Segment itself from that id + the date (ADR 2026-07-19). A
 * `series_id` in that slot loads nothing and 404s — which the sender maps to *success*, so the write
 * evaporates in silence. Hence [definitionId], not `seriesId`.
 *
 * [applyTo] is the pure optimistic transform of the cached [CalendarItem] (the calendar surface acts on
 * feed rows, whose progress is a [WorkingState] — the no-`missed` axis, design-principle #4). [itemId]
 * is the local row id the writer updates; the firing identity ([kind]/[definitionId]/[date]) drives the
 * endpoint + body, and is also what [OccurrenceTargets] encodes into [target].
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [MarkOccurrence] habit | `POST habits/{id}/occurrences` | `{"done":<bool>,"date":"<yyyy-mm-dd>"}` |
 * | [MarkOccurrence] chore | `PUT chores/{id}/occurrences/{date}` | `{"status":"<in_progress\|done\|skipped>"}` |
 * | [MarkOccurrence] event | `POST events/{id}/occurrences/{date}` | `{"action":"<in_progress\|done\|dropped>"}` |
 * | [ClearOccurrence] | `POST {kind}/{id}/occurrences/{date}/clear` | `{}` |
 * | [RescheduleOccurrence] | `POST {kind}/{id}/occurrences/{date}/reschedule` | `{"new_date":"<yyyy-mm-dd>"}` |
 */
sealed interface OccurrenceMutation : Mutation {
    /** The local [CalendarItem] row id the optimistic [applyTo] updates. */
    val itemId: String

    /** Which recurring kind — selects the kind-scoped endpoint + body shape. */
    val kind: ItemKind

    /**
     * The recurring **item** id the occurrence endpoints key on — the chain Head the firing projects
     * from ([CalendarItem.taskId]), *not* the series id (see the class note).
     */
    val definitionId: String

    /** The firing's calendar day (the `{date}` path segment). */
    val date: LocalDate

    override val target: String get() = OccurrenceTargets.of(kind, definitionId, date)

    /** The optimistic local effect on the cached firing row — **pure** and idempotent. */
    fun applyTo(item: CalendarItem): CalendarItem
}

/**
 * Mark a firing (#74). A **habit** is binary — `done = (action == Complete)` with the firing's `date`
 * in the body (the UI offers a habit only Complete). A **chore** or **event** carries the kind-appropriate
 * wire token via [toWireToken]. Optimistically sets the cached row's [WorkingState] (Start -> In-progress,
 * Complete → Done, Skip → Dropped); replay-safe — re-applying yields the same state.
 *
 * All three shapes declare [CollapseRole.Absolute] (#396): a mark fully determines the firing's state, so
 * a later write on the same firing makes this one redundant and the flush-time coalescer may drop it.
 */
data class MarkOccurrence(
    override val itemId: String,
    override val kind: ItemKind,
    override val definitionId: String,
    override val date: LocalDate,
    val action: OccurrenceAction,
) : OccurrenceMutation {
    override fun applyTo(item: CalendarItem): CalendarItem = item.copy(status = action.toWorkingState())

    override fun toRequest(): OutboxRequest = when (kind) {
        ItemKind.Habit -> OutboxRequest(
            OutboxMethod.Post,
            listOf("habits", definitionId, "occurrences"),
            buildJsonObject {
                put("done", action == OccurrenceAction.Complete)
                put("date", date.toString())
            }.toString(),
            acceptsActivityStamp = true,
            collapseRole = CollapseRole.Absolute,
        )
        ItemKind.Chore -> OutboxRequest(
            OutboxMethod.Put,
            listOf("chores", definitionId, "occurrences", date.toString()),
            buildJsonObject { put("status", action.toWireToken(OccurrenceKind.Chore)) }.toString(),
            acceptsActivityStamp = true,
            collapseRole = CollapseRole.Absolute,
        )
        ItemKind.Event -> OutboxRequest(
            OutboxMethod.Post,
            listOf("events", definitionId, "occurrences", date.toString()),
            buildJsonObject { put("action", action.toWireToken(OccurrenceKind.Event)) }.toString(),
            acceptsActivityStamp = true,
            collapseRole = CollapseRole.Absolute,
        )
        ItemKind.Task -> error("MarkOccurrence is only valid for a recurring kind, not Task")
    }
}

/**
 * Clear a firing's status (#74) — the forgiving "let it go back to Scheduled" undo (design-principle #8),
 * uniform across kinds via `POST …/occurrences/{date}/clear`. Optimistically resets the cached row to
 * [WorkingState.Open].
 *
 * The verb is a **POST soft-delete, not a `DELETE`** (#364): the backend retired the bodiless
 * `DELETE …/occurrences/{date}` so Activity-ledger metadata could ride in a body without depending on
 * CDN `DELETE`-body behavior, and it left no alias — the old route is simply gone. The body is
 * `ActivityBody`, whose every field is optional, so an empty `{}` is a valid clear; the `activity`
 * stamp is injected at the outbox choke-point ([ActivityStamp]) rather than built here.
 *
 * Declares [CollapseRole.Absolute] (#396) alongside the marks: a clear is `set_…_occurrence(id, date,
 * None)`, an absolute write of the firing's whole state, and it returns `204` whether or not a status was
 * ever recorded — so collapsing an unsent mark into a later clear cannot error.
 */
data class ClearOccurrence(
    override val itemId: String,
    override val kind: ItemKind,
    override val definitionId: String,
    override val date: LocalDate,
) : OccurrenceMutation {
    override fun applyTo(item: CalendarItem): CalendarItem = item.copy(status = WorkingState.Open)

    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Post,
        listOf(kind.recurringPath(), definitionId, "occurrences", date.toString(), "clear"),
        // An empty object, not null: a null body sends no entity at all, and the stamping decorator
        // needs an object to merge `activity` into. Event-clear declares its body `oneOf [null,
        // ActivityBody]` (the Rust handler types it `Option<Json<…>>`) rather than a bare `$ref`, so it
        // still accepts `activity` — all three kinds carry a client entry id (CONTRACT-NOTES: 36 routes,
        // not the 35 a scan that skips `oneOf` arms finds).
        "{}",
        acceptsActivityStamp = true,
        collapseRole = CollapseRole.Absolute,
    )
}

/**
 * Reschedule a firing to [newDate] (#74). Optimistically moves the cached row to the new day (its
 * start/end times are corrected on the next window reconcile). Offered for **all three** recurring
 * kinds (#380): the backend ships `POST /{habits|chores|events}/{id}/occurrences/{date}/reschedule`
 * over one shared `reschedule_recurring_occurrence`, so the long-standing "Events only in v1" gate was
 * stale doc, not a contract. A habit/chore reschedule marks the origin date dropped with a
 * `rescheduled_to` pointer — a server-sanctioned move, not a failure that would snap the row back.
 *
 * Declares [CollapseRole.Barrier] (#396) **explicitly**, though that is also the default: the barrier is a
 * deliberate statement about this route and belongs beside it, not something a reader has to infer from an
 * omission. A reschedule is an absolute write over *two* days, and its target names only the origin, so
 * collapsing anything into or across it would trade a promise in the contract for a backend detail — and
 * would erase the server-side Activity entry for a mark the user really did perform.
 */
data class RescheduleOccurrence(
    override val itemId: String,
    override val kind: ItemKind,
    override val definitionId: String,
    override val date: LocalDate,
    val newDate: LocalDate,
) : OccurrenceMutation {
    override fun applyTo(item: CalendarItem): CalendarItem = item.copy(date = newDate)

    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Post,
        listOf(kind.recurringPath(), definitionId, "occurrences", date.toString(), "reschedule"),
        buildJsonObject { put("new_date", newDate.toString()) }.toString(),
        acceptsActivityStamp = true,
        collapseRole = CollapseRole.Barrier,
    )
}

/** The kind-scoped recurring endpoint prefix (`habits`/`chores`/`events`) — occurrence + definition routes. */
private fun ItemKind.recurringPath(): String = when (this) {
    ItemKind.Habit -> "habits"
    ItemKind.Chore -> "chores"
    ItemKind.Event -> "events"
    ItemKind.Task -> error("recurring endpoints are only for recurring kinds, not Task")
}

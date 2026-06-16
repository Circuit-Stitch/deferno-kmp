package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
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
import kotlinx.serialization.json.JsonNull
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
 * | [SetDescription] | `PATCH tasks/{id}` | `{"description":"…"}` |
 * | [ClearDescription] | `PATCH tasks/{id}` | `{"description":null}` |
 * | [SetLabels] | `PATCH tasks/{id}` | `{"labels":[…]}` |
 * | [SetPinned] | `PATCH tasks/{id}` | `{"pinned":<bool>}` |
 * | [DeleteTask] | `DELETE tasks/{id}` | *(no body; soft-delete)* |
 * | [PlanAdd] | `POST tasks/plan/add` | `{"task_id":"…","date":"…","tz":"…"}` |
 * | [PlanRemove] | `POST tasks/plan/remove` | `{"task_id":"…","date":"…","tz":"…"}` |
 * | [PlanReorder] | `POST tasks/plan/reorder` | `{"task_ids":[…],"date":"…","tz":"…"}` |
 *
 * **Create now rides the outbox too (#185).** With the backend accepting client-supplied ids
 * (Kyle-Falconer/Deferno#402), an offline create mints the Item UUID up front and enqueues a
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
 * Soft-delete a Task (`DELETE tasks/{id}`, no body — the server tombstones it). The optimistic effect
 * is a local tombstone at [deletedAt] (the writer passes its `now`), so the row drops out of the
 * active list immediately; the post-flush reconcile then converges on the server's tombstone (or its
 * absence from the snapshot — either way the row stays deleted locally, ADR-0001 LWW).
 */
data class DeleteTask(override val taskId: TaskId, val deletedAt: Instant) : TaskMutation {
    override fun applyTo(task: Task): Task = task.copy(deletedAt = deletedAt)
    override fun toRequest(): OutboxRequest = OutboxRequest(OutboxMethod.Delete, listOf("tasks", taskId.value))
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
    OutboxRequest(OutboxMethod.Patch, listOf("tasks", id.value), buildJsonObject(build).toString())

/** A `POST tasks/plan/{action}` whose body is exactly the keys [build] sets. */
private fun postPlan(action: String, build: JsonObjectBuilder.() -> Unit): OutboxRequest =
    OutboxRequest(OutboxMethod.Post, listOf("tasks", "plan", action), buildJsonObject(build).toString())

/** A `PATCH auth/me/settings` whose body is exactly the keys [build] sets — nothing absent (ADR-0011). */
private fun patchSettings(build: JsonObjectBuilder.() -> Unit): OutboxRequest =
    OutboxRequest(OutboxMethod.Patch, listOf("auth", "me", "settings"), buildJsonObject(build).toString())

/**
 * A [Mutation] against one dated firing (an Occurrence) of a recurring definition (#74) — the
 * firing-level sibling of `TaskMutation`. It is the write half of the Calendar surface, deferred at
 * envelope v0.1 (see the class note above) until #71 supplied the firing domain + cache. Unlike a Task
 * intent it targets a `(kind, seriesId, date)` firing, because the occurrence endpoints are kind-scoped
 * (`/habits|chores|events/{seriesId}/occurrences/{date}`). Offline-first (ADR-0001): these target an
 * **existing** server entity, so they ride the normal outbox — *not* online-only like create.
 *
 * [applyTo] is the pure optimistic transform of the cached [CalendarItem] (the calendar surface acts on
 * feed rows, whose progress is a [WorkingState] — the no-`missed` axis, design-principle #4). [itemId]
 * is the local row id the writer updates; the firing identity ([kind]/[seriesId]/[date]) drives the
 * endpoint + body.
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [MarkOccurrence] habit | `POST habits/{id}/occurrences` | `{"done":<bool>,"date":"<yyyy-mm-dd>"}` |
 * | [MarkOccurrence] chore | `PUT chores/{id}/occurrences/{date}` | `{"status":"<in_progress\|done\|skipped>"}` |
 * | [MarkOccurrence] event | `POST events/{id}/occurrences/{date}` | `{"action":"<in_progress\|done\|dropped>"}` |
 * | [ClearOccurrence] | `DELETE {kind}/{id}/occurrences/{date}` | *(no body)* |
 * | [RescheduleOccurrence] | `POST {kind}/{id}/occurrences/{date}/reschedule` | `{"new_date":"<yyyy-mm-dd>"}` |
 */
sealed interface OccurrenceMutation : Mutation {
    /** The local [CalendarItem] row id the optimistic [applyTo] updates. */
    val itemId: String

    /** Which recurring kind — selects the kind-scoped endpoint + body shape. */
    val kind: ItemKind

    /** The recurring definition id the occurrence endpoints key on. */
    val seriesId: String

    /** The firing's calendar day (the `{date}` path segment). */
    val date: LocalDate

    override val target: String get() = "occurrence:${kind.name}:$seriesId:$date"

    /** The optimistic local effect on the cached firing row — **pure** and idempotent. */
    fun applyTo(item: CalendarItem): CalendarItem
}

/**
 * Mark a firing (#74). A **habit** is binary — `done = (action == Complete)` with the firing's `date`
 * in the body (the UI offers a habit only Complete). A **chore** or **event** carries the kind-appropriate
 * wire token via [toWireToken]. Optimistically sets the cached row's [WorkingState] (Start -> In-progress,
 * Complete → Done, Skip → Dropped); replay-safe — re-applying yields the same state.
 */
data class MarkOccurrence(
    override val itemId: String,
    override val kind: ItemKind,
    override val seriesId: String,
    override val date: LocalDate,
    val action: OccurrenceAction,
) : OccurrenceMutation {
    override fun applyTo(item: CalendarItem): CalendarItem = item.copy(status = action.toWorkingState())

    override fun toRequest(): OutboxRequest = when (kind) {
        ItemKind.Habit -> OutboxRequest(
            OutboxMethod.Post,
            listOf("habits", seriesId, "occurrences"),
            buildJsonObject {
                put("done", action == OccurrenceAction.Complete)
                put("date", date.toString())
            }.toString(),
        )
        ItemKind.Chore -> OutboxRequest(
            OutboxMethod.Put,
            listOf("chores", seriesId, "occurrences", date.toString()),
            buildJsonObject { put("status", action.toWireToken(OccurrenceKind.Chore)) }.toString(),
        )
        ItemKind.Event -> OutboxRequest(
            OutboxMethod.Post,
            listOf("events", seriesId, "occurrences", date.toString()),
            buildJsonObject { put("action", action.toWireToken(OccurrenceKind.Event)) }.toString(),
        )
        ItemKind.Task -> error("MarkOccurrence is only valid for a recurring kind, not Task")
    }
}

/**
 * Clear a firing's status (#74) — the forgiving "let it go back to Scheduled" undo (design-principle #8),
 * uniform across kinds via `DELETE …/occurrences/{date}`. Optimistically resets the cached row to
 * [WorkingState.Open].
 */
data class ClearOccurrence(
    override val itemId: String,
    override val kind: ItemKind,
    override val seriesId: String,
    override val date: LocalDate,
) : OccurrenceMutation {
    override fun applyTo(item: CalendarItem): CalendarItem = item.copy(status = WorkingState.Open)

    override fun toRequest(): OutboxRequest =
        OutboxRequest(OutboxMethod.Delete, listOf(kind.occurrencePath(), seriesId, "occurrences", date.toString()))
}

/**
 * Reschedule a firing to [newDate] (#74). Optimistically moves the cached row to the new day (its
 * start/end times are corrected on the next window reconcile). Kept kind-general for forward
 * compatibility, but the UI only offers it for **Events** in v1 — the habit/chore reschedule endpoints
 * are server-side not-yet-implemented, and enqueuing a guaranteed failure would move the row then snap it
 * back, which reads as shaming-by-failure (design-principle #4).
 */
data class RescheduleOccurrence(
    override val itemId: String,
    override val kind: ItemKind,
    override val seriesId: String,
    override val date: LocalDate,
    val newDate: LocalDate,
) : OccurrenceMutation {
    override fun applyTo(item: CalendarItem): CalendarItem = item.copy(date = newDate)

    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Post,
        listOf(kind.occurrencePath(), seriesId, "occurrences", date.toString(), "reschedule"),
        buildJsonObject { put("new_date", newDate.toString()) }.toString(),
    )
}

/** The kind-scoped occurrence endpoint prefix (`habits`/`chores`/`events`). */
private fun ItemKind.occurrencePath(): String = when (this) {
    ItemKind.Habit -> "habits"
    ItemKind.Chore -> "chores"
    ItemKind.Event -> "events"
    ItemKind.Task -> error("occurrence endpoints are only for recurring kinds, not Task")
}

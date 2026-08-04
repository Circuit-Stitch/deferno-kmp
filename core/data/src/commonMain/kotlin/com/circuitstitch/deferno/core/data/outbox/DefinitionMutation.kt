package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.network.mapper.toWireToken
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Instant

/**
 * The recurring-**definition** field edits (#378) — the per-field siblings of [SetDefinitionState],
 * which until this slice was the only recurring intent there was, so the write seam had exactly one
 * verb where the Task seam has twelve.
 *
 * They live in their own file on the [CreateMutation] / [CommentMutation] precedent: [Mutation] is a
 * plain sealed interface with no exhaustive `when` over it and no polymorphic serialization (the outbox
 * persists the *rendered* request, not the intent — OutboxRequest.kt:59-62), so a subtype declared in a
 * separate file of the same package costs the parent file no edit at all.
 *
 * | Intent | Method + endpoint | Minimal body |
 * |---|---|---|
 * | [SetDefinitionTargetDate] | `PATCH {habits\|chores\|events}/{id}` | `{"target_date":"<rfc3339>"}` (a `null` clears) |
 * | [SetDefinitionPriority] | `PATCH {habits\|chores\|events}/{id}` | `{"priority":"<fire\|normal\|backlog>"}` (never `null`) |
 * | [DeleteItem] | `DELETE items/{id}` | *(no body; soft-delete — and kind-neutral, so not in this hierarchy)* |
 *
 * **One optimistic transform, not three.** [SetDefinitionState] carries an `applyTo` overload per kind
 * (Mutation.kt) because Habit/Chore/Event are three unrelated data classes with no supertype to write
 * against. Copying that shape here would have meant six more overloads *and* a second and third
 * `when (kind)` store dispatch in [com.circuitstitch.deferno.core.data.definition.OutboxDefinitionWriter]
 * — three four-arm dispatches, each arm separately uncovered. So these transform a [DefinitionFields]
 * instead: the narrow kind-neutral projection of what a definition edit reads or writes, which lets the
 * writer lift and lower once for every intent.
 */
sealed interface DefinitionMutation : Mutation {

    /** The raw Item id — the chain **Head**, cross-kind like [Move], never a kind-typed id. */
    val id: String

    /** Selects the kind-scoped endpoint (`habits`/`chores`/`events`); a `Task` is rejected loudly. */
    val kind: ItemKind

    override val target: String get() = "item:$id"

    /**
     * The optimistic local effect — a **pure** transform of the cached row's [DefinitionFields] (no side
     * effects, no exceptions). Replay-safe: `apply(apply(f)) == apply(f)`, mirroring the idempotence of
     * the wire intent, so a double-apply never compounds.
     */
    fun apply(fields: DefinitionFields): DefinitionFields

    /**
     * The **old** values of exactly the keys [toRequest]'s body carries, in the same JSON keys/encoding —
     * the "before" half of the Activity ledger's old→new diff, snapshotted from the pre-apply row. The
     * recurring counterpart of `TaskMutation.beforeValues` (Mutation.kt), which cannot be reused: it is
     * an extension on a `Task` receiver with an exhaustive `when` over the Task intents.
     *
     * Non-null, unlike the Task version: every intent here edits a field, and none of them is a delete.
     */
    fun beforeValues(fields: DefinitionFields): JsonObject
}

/**
 * The kind-neutral projection of a cached recurring definition — exactly the fields a
 * [DefinitionMutation] reads or writes. The definition-row analogue of
 * [com.circuitstitch.deferno.core.model.Item], the cross-kind projection `planMove` orders on.
 *
 * It exists because Habit, Chore and Event are three unrelated data classes: without it every intent
 * would need one transform per kind and the writer one store dispatch per method. With it, each intent
 * states its effect once and the writer's single `when (kind)` lifts the row here and lowers it back.
 *
 * [completeBy] is **read-only** here — nothing in this file sets it. It is carried because
 * [SetDefinitionTargetDate] cannot reproduce the server's clamp without seeing the deadline.
 */
data class DefinitionFields(
    val definitionState: DefinitionState,
    val targetDate: Instant?,
    val priority: Priority,
    val completeBy: Instant?,
)

/** Lift a cached Habit onto the kind-neutral [DefinitionFields] a [DefinitionMutation] transforms. */
internal fun Habit.definitionFields(): DefinitionFields =
    DefinitionFields(definitionState, targetDate, priority, completeBy)

/** Lower a transformed [DefinitionFields] back onto the cached Habit — only the writable three. */
internal fun Habit.withDefinitionFields(fields: DefinitionFields): Habit =
    copy(definitionState = fields.definitionState, targetDate = fields.targetDate, priority = fields.priority)

/** Lift a cached Chore onto its [DefinitionFields] (the [Habit] twin — the three kinds share no supertype). */
internal fun Chore.definitionFields(): DefinitionFields =
    DefinitionFields(definitionState, targetDate, priority, completeBy)

/** Lower a transformed [DefinitionFields] back onto the cached Chore. */
internal fun Chore.withDefinitionFields(fields: DefinitionFields): Chore =
    copy(definitionState = fields.definitionState, targetDate = fields.targetDate, priority = fields.priority)

/** Lift a cached Event onto its [DefinitionFields]. */
internal fun Event.definitionFields(): DefinitionFields =
    DefinitionFields(definitionState, targetDate, priority, completeBy)

/** Lower a transformed [DefinitionFields] back onto the cached Event. */
internal fun Event.withDefinitionFields(fields: DefinitionFields): Event =
    copy(definitionState = fields.definitionState, targetDate = fields.targetDate, priority = fields.priority)

/**
 * Set (or clear) a recurring definition's **soft target date** (#375, #378) — the recurring sibling of
 * `SetTargetDate`. One intent with a nullable operand (the `SetDeadlineTime` shape, not the older
 * `SetDeadline`/`ClearDeadline` split): it maps exactly onto the server's `Patch<DateTime<Utc>>` — a
 * value sets, an explicit `null` clears, an omitted key leaves unchanged. Since an omitted key is a
 * silent no-op server-side, the clear MUST emit an explicit `null` (ADR-0011).
 *
 * **The optimistic apply clamps; the request deliberately does not.** See [clampTargetDate].
 */
data class SetDefinitionTargetDate(
    override val id: String,
    override val kind: ItemKind,
    val targetDate: Instant?,
) : DefinitionMutation {

    override fun apply(fields: DefinitionFields): DefinitionFields =
        fields.copy(targetDate = clampTargetDate(targetDate, fields.completeBy))

    override fun beforeValues(fields: DefinitionFields): JsonObject =
        buildJsonObject { putTargetDate(fields.targetDate) }

    override fun toRequest(): OutboxRequest = patchRecurring(kind, id) { putTargetDate(targetDate) }
}

/**
 * Set a recurring definition's urgency bucket (#375, #378) — `fire`/`normal`/`backlog` via
 * [Priority.toWireToken]. The recurring sibling of `SetPriority`, and it inherits that intent's one
 * hard rule: **never emit `null`.**
 *
 * The server types this `Option<Priority>` with a plain `#[serde(default)]` and none of the strict
 * deserializer its `status` field uses (backend/src/payloads.rs), so there is no null form — `priority`
 * is never absent on a row, it defaults to `Normal`, and "clearing" it is spelled
 * `SetDefinitionPriority(Normal)`. A `JsonNull` here would fold to `None`, indistinguishable from omit,
 * and the write would succeed as a **200 no-op**: nothing fails, nothing retries, nothing dead-letters,
 * and the optimistic value simply stays wrong until the next reconcile quietly reverts it. Silence is
 * why the rule matters — a loud rejection would at least be observable.
 */
data class SetDefinitionPriority(
    override val id: String,
    override val kind: ItemKind,
    val priority: Priority,
) : DefinitionMutation {

    override fun apply(fields: DefinitionFields): DefinitionFields = fields.copy(priority = priority)

    // The old bucket is always a real value (never absent — it defaults to Normal), so unlike
    // [SetDefinitionTargetDate] this before-image has no null branch to consider.
    override fun beforeValues(fields: DefinitionFields): JsonObject =
        buildJsonObject { put("priority", fields.priority.toWireToken()) }

    override fun toRequest(): OutboxRequest = patchRecurring(kind, id) { put("priority", priority.toWireToken()) }
}

/**
 * Soft-delete an Item of **any** kind (`DELETE items/{id}`, no body) — the route the webui calls for
 * every kind (webui/src/api/items.ts:114-134; its `deleteHabit`/`deleteChore`/`deleteEvent` at :337,
 * :363, :391 all delegate to this one function).
 *
 * **Not `DELETE /{kind}/{id}`,** which is the tempting-looking mirror of [patchRecurring] and is wrong.
 * That route binds `archive_habit`/`archive_chore`/`archive_event`, which soft-deletes **one Segment**
 * of a recurrence chain and deliberately leaves the rest alive (backend/src/handlers/habits.rs:720-726,
 * :755-760) — so a definition whose rule was ever edited would keep a live sibling Segment, and the
 * survivor pops back into the next snapshot as an item the user just deleted. `DELETE /items/{id}`
 * resolves the kind server-side and expands the chain: *"Deleting any Segment deletes the WHOLE Series
 * chain (#574) … the chain is the unit of identity, so it is also the unit of deletion"*
 * (backend/src/handlers/items.rs:1127-1134). One path, so no kind operand and no kind→path mapping.
 *
 * **It is a soft delete and it does NOT cascade.** `soft_delete_habit` sets `deleted_at` and unhooks the
 * row from the org index, root order, daily plans, search and pinned set — it never removes occurrence
 * records and never calls `delete_recurrence_series` (only the *hard* `delete_habit` does:
 * backend/src/repository/habits.rs:429 vs :436-486). Confirmation copy must never promise that the
 * occurrences or the recurrence series go with it.
 *
 * Cross-kind like [Move], so it carries no single-kind `applyTo`: the optimistic tombstone spans the
 * four per-kind stores and lives in [com.circuitstitch.deferno.core.data.item.OutboxItemWriter]. It is
 * therefore **not** a [DefinitionMutation] — it takes no [ItemKind] and works on a Task too.
 *
 * No `activity` stamp, for `DeleteTask`'s reason (Mutation.kt): the backend's soft-delete migration
 * covered comments, attachments and occurrence-clears but NOT item delete, so this stays a bodiless
 * `DELETE` with no entity to merge a stamp into. Replay is idempotent for free — the handler returns
 * early when the row is already tombstoned, and a `404` maps to success anyway
 * (KtorOutboxRequestSender.outcomeFor).
 */
data class DeleteItem(val id: String) : Mutation {
    override val target: String get() = "item:$id"
    override fun toRequest(): OutboxRequest = OutboxRequest(OutboxMethod.Delete, listOf("items", id))
}

/**
 * The client-side mirror of the backend's `clamp_target_date` (#629,
 * backend/src/models/item_kind.rs:144-150), invoked from `apply_write_invariants` (:183) — the single
 * chokepoint every content mutation on all four kinds funnels through. A `target_date` later than
 * `complete_by` is silently pulled **down** to the deadline and stored clamped, with no error and no
 * rejection to observe.
 *
 * So the two dates are **not** "peers, fully independent, all four combinations valid" — the claim both
 * `SetTargetDate`'s KDoc (Mutation.kt) and `SetTaskTargetDate`'s (core/domain `Command.kt`) still make.
 * They are a soft date **bounded by** a hard one. Applying the clamp locally is what makes the
 * optimistic row equal what the server will actually store, instead of a value the next reconcile
 * quietly reverts. It matters most on the commonest recurring shape: a lapsed Habit whose `complete_by`
 * cursor sits in the past, where an unclamped future target would appear to save and then vanish, and
 * the control would look broken.
 *
 * **Only the apply clamps — [SetDefinitionTargetDate.toRequest] sends the raw value.** The server holds
 * the authoritative `complete_by` and clamps against *that*; our cached copy may be stale, and clamping
 * the wire value too would let a stale local deadline overwrite a date the server would have kept.
 * Clamping is idempotent, so sending raw costs nothing.
 */
internal fun clampTargetDate(targetDate: Instant?, completeBy: Instant?): Instant? =
    if (targetDate != null && completeBy != null && targetDate > completeBy) completeBy else targetDate

/** `target_date` as a set-or-clear key — an explicit `null` means "clear it", never omit (ADR-0011). */
private fun JsonObjectBuilder.putTargetDate(value: Instant?) {
    if (value == null) put("target_date", JsonNull) else put("target_date", value.toString())
}

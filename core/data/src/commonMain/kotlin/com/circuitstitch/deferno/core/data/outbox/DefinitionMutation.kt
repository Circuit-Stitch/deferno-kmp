package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.plugin.Anchor
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Targeted
import com.circuitstitch.deferno.core.model.plugin.loading
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
 * **One optimistic transform, not three.** These transformed a `DefinitionFields` until #422 — a narrow
 * kind-neutral projection of what a definition edit reads or writes, hand-written because Habit, Chore
 * and Event are three data classes with no supertype, and lifted and lowered by a `when (kind)` in the
 * writer. The cache holds the plugin-shaped record now, so the projection is the record and the lift is
 * gone. Each transform is a Family swap.
 */
sealed interface DefinitionMutation : Mutation {

    /** The raw Item id — the chain **Head**, cross-kind like [Move], never a kind-typed id. */
    val id: String

    /** Selects the kind-scoped endpoint (`habits`/`chores`/`events`); a `Task` is rejected loudly. */
    val kind: ItemKind

    override val target: String get() = "item:$id"

    /**
     * The optimistic local effect — a **pure** transform of the cached [item] (no side effects, no
     * exceptions). Replay-safe: `apply(apply(i)) == apply(i)`, mirroring the idempotence of the wire
     * intent, so a double-apply never compounds.
     */
    fun apply(item: Item): Item

    /**
     * The **old** values of exactly the keys [toRequest]'s body carries, in the same JSON keys and
     * encoding — the "before" half of the Activity ledger's old-to-new diff, snapshotted from the
     * pre-apply record. The recurring counterpart of `TaskMutation.beforeValues` (Mutation.kt), which
     * cannot be reused: it has an exhaustive `when` over the Task intents.
     *
     * Non-null, unlike the Task version: every intent here edits a field, and none of them is a delete.
     */
    fun beforeValues(item: Item): JsonObject
}

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

    // The deadline the clamp reads is the Temporal Family's, which on a recurring definition is the
    // Recurrence cursor rather than a bound (#439). That is what the field held before the re-cut too:
    // the clamp has always been reading `complete_by`.
    override fun apply(item: Item): Item =
        item.copy(
            plugins = item.plugins.loading(
                Targeted(clampTargetDate(targetDate, (item.anchor as? Anchor.Deadline)?.completeBy)),
            ),
        )

    override fun beforeValues(item: Item): JsonObject =
        buildJsonObject { putTargetDate(item.targeted.targetDate) }

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

    override fun apply(item: Item): Item =
        item.copy(plugins = item.plugins.loading(item.priority.copy(priority = priority)))

    // The old bucket is always a real value (never absent — it defaults to Normal), so unlike
    // [SetDefinitionTargetDate] this before-image has no null branch to consider.
    override fun beforeValues(item: Item): JsonObject =
        buildJsonObject { put("priority", item.priority.priority.toWireToken()) }

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

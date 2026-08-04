package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.DefinitionFields
import com.circuitstitch.deferno.core.data.outbox.DefinitionMutation
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionPriority
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionState
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionTargetDate
import com.circuitstitch.deferno.core.data.outbox.definitionFields
import com.circuitstitch.deferno.core.data.outbox.withDefinitionFields
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.network.mapper.toWireToken
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [DefinitionWriter] (ADR-0001, #299/#378) — the recurring-definition mirror of
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. A definition spans three per-kind stores
 * (Habit/Chore/Event), so it holds all three (like [com.circuitstitch.deferno.core.data.item.OutboxItemWriter])
 * plus the outbox.
 *
 * Every method funnels through one [submit]: it builds the intent, applies that intent's pure optimistic
 * transform to the cached row of the selected [ItemKind] (inside that store's `transaction`, so a
 * concurrent reconcile can't interleave — same atomicity argument as `OutboxTaskWriter`), then enqueues
 * the idempotent `PATCH {kind}/{id}` request for replay. A target row that isn't cached still enqueues
 * (the write isn't lost) but skips the apply — the reconcile after replay materialises server truth (LWW).
 *
 * **One store dispatch for the whole seam.** The `when (kind)` lives in [submit] alone. Inlining it per
 * method — the shape this class had while it was a one-method seam — would have grown one four-arm
 * dispatch per verb, each arm a separately-reachable branch that only its own test covers. The intents
 * express their effect over the kind-neutral [DefinitionFields] projection precisely so this dispatch can
 * be written once, lifting each cached row onto that projection and lowering the transformed one back.
 *
 * **The Activity before-image is captured here, pre-apply.** [submit] snapshots the intent's
 * `beforeValues` from the row *before* the transform overwrites it, inside the same transaction, and
 * passes it as `enqueue`'s fourth argument — the shape `OutboxTaskWriter.submit` established. Until #378
 * this class used the 3-arg enqueue and let `before` default to `null`, so archiving a habit rendered
 * "status: (unavailable) → archived" in the Trail; the ledger prefers the local capture, so nothing else
 * was ever going to fill that in.
 *
 * [now] is injected (default the system clock) so the enqueue time is deterministic under test (ADR-0006).
 */
class OutboxDefinitionWriter(
    private val habitStore: HabitLocalStore,
    private val choreStore: ChoreLocalStore,
    private val eventStore: EventLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : DefinitionWriter {

    override suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState) {
        // SetDefinitionState predates [DefinitionMutation] and lives in Mutation.kt with a typed `applyTo`
        // overload per kind, so it can't be handed to submit(DefinitionMutation) directly. Its effect on
        // the projection is the same one-liner its three overloads each spell out, so it is adapted here
        // rather than the shared dispatch being duplicated for it.
        val mutation = SetDefinitionState(id, kind, target)
        submit(
            id = id,
            kind = kind,
            target = mutation.target,
            request = mutation.toRequest(),
            apply = { it.copy(definitionState = target) },
            beforeValues = { buildJsonObject { put("status", it.definitionState.toWireToken()) } },
        )
    }

    override suspend fun setTargetDate(id: String, kind: ItemKind, targetDate: Instant?) =
        submit(SetDefinitionTargetDate(id, kind, targetDate))

    override suspend fun setPriority(id: String, kind: ItemKind, priority: Priority) =
        submit(SetDefinitionPriority(id, kind, priority))

    /** Every [DefinitionMutation] already states its own transform + before-image over [DefinitionFields]. */
    private suspend fun submit(mutation: DefinitionMutation) = submit(
        id = mutation.id,
        kind = mutation.kind,
        target = mutation.target,
        request = mutation.toRequest(),
        apply = mutation::apply,
        beforeValues = mutation::beforeValues,
    )

    /**
     * The one per-kind store dispatch: read the cached row, snapshot [beforeValues] off it, write
     * [apply]'s result back — all inside that store's transaction — then enqueue with the before-image.
     */
    private suspend fun submit(
        id: String,
        kind: ItemKind,
        target: String,
        request: OutboxRequest,
        apply: (DefinitionFields) -> DefinitionFields,
        beforeValues: (DefinitionFields) -> JsonObject,
    ) {
        var before: String? = null
        when (kind) {
            ItemKind.Habit -> habitStore.transaction { store ->
                store.get(HabitId(id))?.let { current ->
                    val fields = current.definitionFields()
                    before = beforeValues(fields).toString()
                    store.upsert(current.withDefinitionFields(apply(fields)))
                }
            }
            ItemKind.Chore -> choreStore.transaction { store ->
                store.get(ChoreId(id))?.let { current ->
                    val fields = current.definitionFields()
                    before = beforeValues(fields).toString()
                    store.upsert(current.withDefinitionFields(apply(fields)))
                }
            }
            ItemKind.Event -> eventStore.transaction { store ->
                store.get(EventId(id))?.let { current ->
                    val fields = current.definitionFields()
                    before = beforeValues(fields).toString()
                    store.upsert(current.withDefinitionFields(apply(fields)))
                }
            }
            // A Task has no definition state and no recurring endpoint — guard rather than silently
            // enqueue a bad route (the same guard `ItemKind.recurringPath()` makes at the path level).
            ItemKind.Task -> error("a recurring-definition write is only valid for a recurring kind, not Task")
        }
        outbox.enqueue(target, request, now(), before)
    }
}

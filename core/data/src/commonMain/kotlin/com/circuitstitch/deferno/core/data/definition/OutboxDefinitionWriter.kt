package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.data.outbox.DefinitionMutation
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionPriority
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionState
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionTargetDate
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.plugin.Item
import com.circuitstitch.deferno.core.model.plugin.Lifecycle
import com.circuitstitch.deferno.core.network.mapper.toWireToken
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [DefinitionWriter] (ADR-0001, #299/#378) — the recurring-definition mirror of
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. It held all three per-kind stores until
 * #422 flipped the cache onto plugins; it holds the one store and the outbox now.
 *
 * Every method funnels through one [submit]: it builds the intent, applies that intent's pure optimistic
 * transform to the cached record (inside the store's `transaction`, so a concurrent reconcile cannot
 * interleave — the same atomicity argument as `OutboxTaskWriter`), then enqueues the idempotent
 * `PATCH {kind}/{id}` request for replay. A target row that is not cached still enqueues, so the write
 * is not lost, and skips the apply — the reconcile after replay materialises server truth (LWW).
 *
 * **The store dispatch is gone.** A `when (kind)` in [submit] picked which of three stores held the row,
 * and the intents expressed themselves over a hand-written kind-neutral projection so that dispatch
 * could be written once rather than once per verb. One cache and one record removed both. [ItemKind]
 * survives here only to pick the endpoint and to refuse a Task.
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
    private val items: ItemLocalStore,
    private val outbox: OutboxStore,
    private val now: () -> Instant = { Clock.System.now() },
) : DefinitionWriter {

    override suspend fun setDefinitionState(id: String, kind: ItemKind, target: DefinitionState) {
        // SetDefinitionState predates [DefinitionMutation] and lives in Mutation.kt, so it cannot be
        // handed to submit(DefinitionMutation) directly. Its transform is now the same shape as theirs,
        // so it is adapted here rather than the shared dispatch being duplicated for it.
        val mutation = SetDefinitionState(id, kind, target)
        submit(
            id = id,
            kind = kind,
            target = mutation.target,
            request = mutation.toRequest(),
            apply = mutation::applyTo,
            beforeValues = { item ->
                buildJsonObject { put("status", item.definitionState().toWireToken()) }
            },
        )
    }

    override suspend fun setTargetDate(id: String, kind: ItemKind, targetDate: Instant?) =
        submit(SetDefinitionTargetDate(id, kind, targetDate))

    override suspend fun setPriority(id: String, kind: ItemKind, priority: Priority) =
        submit(SetDefinitionPriority(id, kind, priority))

    /** Every [DefinitionMutation] already states its own transform and before-image over the record. */
    private suspend fun submit(mutation: DefinitionMutation) = submit(
        id = mutation.id,
        kind = mutation.kind,
        target = mutation.target,
        request = mutation.toRequest(),
        apply = mutation::apply,
        beforeValues = mutation::beforeValues,
    )

    /**
     * Read the cached record, snapshot [beforeValues] off it, write [apply]'s result back — all inside
     * one store transaction — then enqueue with the before-image.
     */
    private suspend fun submit(
        id: String,
        kind: ItemKind,
        target: String,
        request: OutboxRequest,
        apply: (Item) -> Item,
        beforeValues: (Item) -> JsonObject,
    ) {
        // A Task has no definition state and no recurring endpoint — guard rather than silently enqueue
        // a bad route (the same guard `ItemKind.recurringPath()` makes at the path level). It is the
        // only thing the kind still decides here.
        require(kind != ItemKind.Task) {
            "a recurring-definition write is only valid for a recurring kind, not Task"
        }
        var before: String? = null
        items.transaction { store ->
            store.get(id)?.let { current ->
                before = beforeValues(current.item).toString()
                store.upsert(current.copy(item = apply(current.item)))
            }
        }
        outbox.enqueue(target, request, now(), before)
    }

    /** This record's light switch, or the wire default a row with no status decodes to. */
    private fun Item.definitionState(): DefinitionState =
        (progress.lifecycle as? Lifecycle.Definition)?.state ?: DefinitionState.Active
}

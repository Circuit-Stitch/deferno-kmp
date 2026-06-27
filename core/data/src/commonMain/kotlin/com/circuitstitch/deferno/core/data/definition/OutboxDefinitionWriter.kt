package com.circuitstitch.deferno.core.data.definition

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.SetDefinitionState
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The offline-first [DefinitionWriter] (ADR-0001, #299) — the recurring-definition mirror of
 * [com.circuitstitch.deferno.core.data.task.OutboxTaskWriter]. A definition spans three per-kind stores
 * (Habit/Chore/Event), so it holds all three (like [com.circuitstitch.deferno.core.data.item.OutboxItemWriter])
 * plus the outbox.
 *
 * [setDefinitionState] builds the [SetDefinitionState] intent, applies its pure optimistic transform to
 * the cached row of the selected [ItemKind] (inside that store's [transaction], so a concurrent reconcile
 * can't interleave — same atomicity argument as `OutboxTaskWriter`), then enqueues the idempotent
 * `PATCH {kind}/{id}` request for replay. A target row that isn't cached still enqueues (the write isn't
 * lost) but skips the apply — the reconcile after replay materialises server truth (LWW).
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
        val mutation = SetDefinitionState(id, kind, target)
        when (kind) {
            ItemKind.Habit -> habitStore.transaction { store ->
                store.get(HabitId(id))?.let { store.upsert(mutation.applyTo(it)) }
            }
            ItemKind.Chore -> choreStore.transaction { store ->
                store.get(ChoreId(id))?.let { store.upsert(mutation.applyTo(it)) }
            }
            ItemKind.Event -> eventStore.transaction { store ->
                store.get(EventId(id))?.let { store.upsert(mutation.applyTo(it)) }
            }
            // A Task has no definition state — guard rather than silently enqueue a bad route.
            ItemKind.Task -> error("setDefinitionState is only valid for a recurring kind, not Task")
        }
        outbox.enqueue(mutation.target, mutation.toRequest(), now())
    }
}

package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.create.PendingCreateStore
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.recipe.KindRecipe
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe

/**
 * The offline-first cold sync over the `GET /items` snapshot (ADR-0049, #226): one pull, reconciled
 * into the local item cache so the whole tree persists offline and not just its Tasks.
 *
 * **One reconcile, not four.** It ran per kind while the cache held four tables (#422). Collapsing it
 * also fixes a case the split version could not express: a row whose kind changed server-side used to
 * be an insert in one table and an orphan purge in another, in that order, and the two reconciles were
 * separate transactions.
 *
 * **Done-visibility window — honored for free.** `/items` is windowed **server-side**, so this does
 * **no** client-side window math: a terminal, non-recurring item aged past the window is simply absent
 * from the snapshot and the orphan purge drops it. Recurring kinds are never returned as terminal, so
 * they never age out.
 *
 * **Reconcile, atomic.** Inside one [ItemLocalStore.transaction]: upsert every snapshot row by id, then
 * hard-delete the ids the snapshot dropped entirely (`allIds - snapshotIds`). `/items` rows are always
 * [com.circuitstitch.deferno.core.model.HydrationState.Full], so an upsert replaces wholesale and needs
 * no summary-downgrade merge. Tombstones in the snapshot are kept — the upsert stores them as deleted
 * rows — so a re-run is idempotent (ADR-0001 LWW).
 *
 * **Offline-first (ADR-0001).** An [RemoteSnapshot.Unavailable] pull could not reach the server and
 * skips the reconcile entirely, leaving the cache intact. An [RemoteSnapshot.Available] snapshot always
 * reconciles, even when empty, so a genuinely emptied server purges the cache.
 *
 * **Why a wire-model bug looks exactly like being offline — the #381 hazard, worth knowing before you
 * add a DTO field.** [RemoteSnapshot] is binary: `asSnapshot()` collapses *every* `ApiResult.Failure`
 * to [RemoteSnapshot.Unavailable], and a body that failed to deserialize is one of them
 * (`ApiError.Transport`). So a DTO whose shape does not match the wire is indistinguishable here from a
 * dropped connection, and because the early return sits over the whole reconcile, one un-decodable Habit
 * freezes the Task rows too. It is silent by construction: the intended, correct offline behaviour and a
 * total cold-sync stall take the same code path and surface no error.
 *
 * That is why the `/items` DTOs must be **tolerant, never strict**: `ignoreUnknownKeys` for additive
 * fields, a defaulted `...Wire.Unknown` for every enum, defaults for optional fields, and flat tolerant
 * classes rather than sealed hierarchies for nested wire objects (`DefernoJson` registers no
 * `polymorphicDefaultDeserializer`, so an unknown discriminator throws). A shape assumption asserted
 * strictly here is not a loud failure — it is an invisible one.
 *
 * **Offline creates protected from the purge (#185).** A row created offline rides the outbox and is
 * absent from the server snapshot until its create replays, so the still-[pending][PendingCreateStore]
 * ids are excluded from the orphan set.
 */
class ItemSync(
    private val store: ItemLocalStore,
    private val source: ItemSnapshotSource,
    private val pendingCreateStore: PendingCreateStore,
    private val recipe: KindRecipe = ParityRecipe,
) {

    suspend fun refresh() {
        val snapshot = when (val result = source.fetchAll()) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }
        val pending = pendingCreateStore.pendingIds()

        // The snapshot still arrives as four typed lists, because the wire still speaks four kinds
        // (ADR-0056). Each row crosses into the plugin-shaped record here, at the boundary, and the kind
        // rides alongside as the note of which endpoint it came from.
        val rows = buildList {
            snapshot.tasks.forEach { add(CachedItem(recipe.read(it), ItemKind.Task)) }
            snapshot.habits.forEach { add(CachedItem(recipe.read(it), ItemKind.Habit)) }
            snapshot.chores.forEach { add(CachedItem(recipe.read(it), ItemKind.Chore)) }
            snapshot.events.forEach { add(CachedItem(recipe.read(it), ItemKind.Event)) }
        }
        val snapshotIds = rows.mapTo(mutableSetOf()) { it.id }

        store.transaction { s ->
            for (row in rows) s.upsert(row)
            for (orphan in s.allIds() - snapshotIds - pending) s.delete(orphan)
        }
    }
}

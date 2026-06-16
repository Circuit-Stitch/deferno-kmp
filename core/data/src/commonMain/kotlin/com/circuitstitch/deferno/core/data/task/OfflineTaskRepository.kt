package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.create.PendingCreateStore
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow

/**
 * The offline-first [TaskRepository] (ADR-0001, #22): the local store is the single source of truth,
 * reads are its `Flow`s, and the network only ever *writes through* the store via [refresh]/[hydrate].
 *
 * **Reconcile ([refresh]).** The API exposes no `?since=`/`rev`/`ETag` (CONTRACT-NOTES -> Sync), so
 * a refresh pulls the **full snapshot** of summaries and reconciles by UUID `id` in one transaction:
 *
 * - **Upsert by id, honoring tombstones.** Every snapshot row is written by id. A snapshot row that
 *   is itself a tombstone (`deletedAt != null`) is stored *as a tombstone* — kept, not purged — so a
 *   re-run of the same snapshot is idempotent (ADR-0001 LWW).
 * - **Hydration preservation (never downgrade).** List endpoints return summaries, so a snapshot row
 *   for an id we have already hydrated to [HydrationState.Full] carries `null` enrichment. Blindly
 *   upserting it would wipe the `description`/`ownerOrgId`/`nextTaskId` the user just fetched. So when
 *   the existing row is Full and the incoming row is Summary, we **merge**: take the snapshot's
 *   summary fields but keep the existing full-only enrichment and stay Full. (A snapshot row that is
 *   *itself* Full — e.g. from `/items` — replaces wholesale.)
 * - **Remove locally-absent.** An id we hold that is *entirely absent* from the snapshot was deleted
 *   server-side and is no longer even returned as a tombstone, so it is hard-deleted locally. We
 *   diff `local.allIds() - snapshotIds` (in Kotlin — the schema avoids the empty `NOT IN ()` footgun).
 *
 * An [RemoteSnapshot.Unavailable] pull (the server couldn't be reached) skips the reconcile entirely,
 * so a refresh that can't reach the server leaves the cache intact. An [RemoteSnapshot.Available]
 * snapshot is always reconciled — even when it is *empty* (a server that genuinely emptied), which the
 * "remove locally-absent" diff then purges. (The old `emptyList()`-means-offline conflation could never
 * purge a cache to empty, since empty read as "couldn't fetch".)
 *
 * **Hydration ([hydrate]).** Opening a Task pulls its full detail and upserts it, upgrading the
 * cached row summary -> full; a missing/failed detail is a no-op (the summary stays).
 *
 * **Offline-created rows are protected from the purge (#185).** A Task created offline has a
 * client-generated id and rides the outbox; until its create replays it is absent from the server
 * snapshot, so the "remove locally-absent" diff would wrongly purge it. The [pendingCreateStore]'s
 * still-pending ids are excluded from the orphan set (the create-side mirror of the settings reconcile
 * reading the outbox so a refresh can't clobber an un-synced change, #143). Confirmation of a replayed
 * create is owned by the outbox replay listener (tied to replay), not this refresh.
 */
class OfflineTaskRepository(
    private val localStore: TaskLocalStore,
    private val remoteSource: TaskRemoteSource,
    private val pendingCreateStore: PendingCreateStore,
) : TaskRepository {

    override fun observeTasks(): Flow<List<Task>> = localStore.observeActive()

    override fun observeTask(id: TaskId): Flow<Task?> = localStore.observe(id)

    override suspend fun refresh() {
        // Unavailable = couldn't reach the server → skip, leaving the cache intact (offline-first).
        // An Available snapshot reconciles even when empty: a genuinely-empty server purges the cache.
        val snapshot = when (val result = remoteSource.fetchAll()) {
            is RemoteSnapshot.Available -> result.value
            RemoteSnapshot.Unavailable -> return
        }

        val snapshotIds = snapshot.mapTo(mutableSetOf()) { it.id }
        // Offline-created Tasks still awaiting replay are absent from the snapshot by design — protect
        // them from the orphan-purge (#185) until their create lands (the listener confirms them then).
        val pendingCreated = pendingCreateStore.pendingIds().mapTo(mutableSetOf(), ::TaskId)

        localStore.transaction { store ->
            for (incoming in snapshot) {
                store.upsert(reconciled(store.get(incoming.id), incoming))
            }
            // Hard-delete the rows the snapshot dropped entirely (not even returned as tombstones),
            // except offline creates that haven't replayed yet.
            for (orphan in store.allIds() - snapshotIds - pendingCreated) {
                store.delete(orphan)
            }
        }
    }

    override suspend fun hydrate(id: TaskId) {
        val detail = remoteSource.fetch(id) ?: return
        localStore.upsert(detail)
    }

    /**
     * Global search (#73): online-only, one-shot. Guards the contract's 2-char minimum (a too-short
     * term short-circuits to an empty [TaskSearchResult.Success] with no round trip), delegates to
     * [TaskRemoteSource.search] (a failed pull stays [TaskSearchResult.Unavailable] — visible to the
     * foreground search UI, unlike the silent background reads), then applies the **client-side**
     * [SearchSort]. Results are intentionally **not** upserted into the live cache: search is a
     * separate read surface from the observed [observeTasks] list (ADR-0001 keeps that local-only).
     */
    override suspend fun search(query: TaskSearchQuery): TaskSearchResult {
        if (query.query.trim().length < MIN_SEARCH_QUERY_LENGTH) return TaskSearchResult.Success(emptyList())
        return when (val result = remoteSource.search(query)) {
            is TaskSearchResult.Success ->
                TaskSearchResult.Success(result.tasks.sortedWith(query.sort.comparator()))
            TaskSearchResult.Unavailable -> result
        }
    }

    private fun SearchSort.comparator(): Comparator<Task> = when (this) {
        SearchSort.Relevance -> Comparator { _, _ -> 0 } // keep the server's ranking
        SearchSort.TitleAsc -> compareBy { it.title.lowercase() }
        // Soonest deadline first; tasks without a deadline sort last (nulls last).
        SearchSort.DeadlineAsc -> compareBy(nullsLast()) { it.completeBy }
    }

    /**
     * Merges an [incoming] snapshot row against the [existing] cached row so a summary refresh never
     * downgrades an already-full row (see the class doc). When [existing] is Full and [incoming] is a
     * Summary, the incoming summary fields win but the existing enrichment + Full state are kept;
     * otherwise the incoming row is taken as-is (a new row, a tombstone, or a genuinely full update).
     */
    private fun reconciled(existing: Task?, incoming: Task): Task =
        if (existing?.hydration == HydrationState.Full && incoming.hydration == HydrationState.Summary) {
            incoming.copy(
                hydration = HydrationState.Full,
                ownerOrgId = existing.ownerOrgId,
                description = existing.description,
                nextTaskId = existing.nextTaskId,
                // finishedAt is omitted by the summary wire endpoint (always null on an incoming
                // summary), so preserve the hydrated value when merging a summary over a full row.
                finishedAt = incoming.finishedAt ?: existing.finishedAt,
            )
        } else {
            incoming
        }
}

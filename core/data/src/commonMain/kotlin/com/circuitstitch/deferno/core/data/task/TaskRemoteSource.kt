package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The Task-detail network port (ADR-0001, #22). It speaks the *domain* [Task] — the wire DTO ugliness is
 * condensed at the network edge by the #18 mappers — so the repository never touches a DTO. The cold list
 * snapshot is **no longer** pulled here: as of ADR-0034 (#226) it migrated to the item-wide `GET /items`
 * ([com.circuitstitch.deferno.core.data.item.ItemSnapshotSource]); and global search went **offline** in
 * #311 (a local read over the cache, ADR-0042), so it no longer pulls `/tasks/search` either. This port
 * now carries only the per-item detail:
 *
 * - [fetch] pulls the **full single-item** detail (`/tasks/{id}`) that hydrates a summary on open.
 *
 * Offline-first contract (ADR-0001): a failed background read does not throw — [fetch] returns `null` on
 * failure-or-missing (a no-op-on-null hydrate has no purge to get wrong).
 */
interface TaskRemoteSource {

    /** The full (hydrated) Task for [id], or `null` when missing / on failure. */
    suspend fun fetch(id: TaskId): Task?
}

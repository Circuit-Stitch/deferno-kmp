package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The network port the Task repository refreshes through (ADR-0001, #22). It speaks the *domain*
 * [Task] — the wire DTO ugliness is condensed at the network edge by the #18 mappers — so the
 * repository never touches a DTO. Two reads, matching the API's two shapes:
 *
 * - [fetchAll] pulls the **full snapshot** of list summaries (`/tasks`) the reconcile diffs against.
 * - [fetch] pulls the **full single-item** detail (`/tasks/{id}`) that hydrates a summary on open.
 *
 * Offline-first contract (ADR-0001): a failed call returns `emptyList()`/`null` rather than throwing,
 * so a refresh that can't reach the server leaves the local cache intact instead of wiping it.
 */
interface TaskRemoteSource {

    /** The full snapshot of summary Tasks; `emptyList()` on failure (cache left intact). */
    suspend fun fetchAll(): List<Task>

    /** The full (hydrated) Task for [id], or `null` when missing / on failure. */
    suspend fun fetch(id: TaskId): Task?
}

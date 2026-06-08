package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * Scriptable [TaskRemoteSource] for the repository tests (#22). A test sets [snapshot] to the
 * summaries the next [refresh] sees, and [details] to the full payloads a [hydrate] fetches, then
 * asserts how the repository reconciles them into the local store. [failNext] simulates the
 * offline-first failure path (a refresh/hydrate that can't reach the server returns nothing).
 */
class FakeTaskRemoteSource(
    var snapshot: List<Task> = emptyList(),
    var details: Map<TaskId, Task> = emptyMap(),
    var failNext: Boolean = false,
    /** Rows the next [search] returns (in server order); the repo applies the client sort over these. */
    var searchResults: List<Task> = emptyList(),
) : TaskRemoteSource {

    /** The [TaskSearchQuery] the last [search] received, for asserting the filters reached the wire. */
    var lastSearchQuery: TaskSearchQuery? = null
        private set

    override suspend fun fetchAll(): List<Task> {
        if (failNext) return emptyList()
        return snapshot
    }

    override suspend fun fetch(id: TaskId): Task? {
        if (failNext) return null
        return details[id]
    }

    override suspend fun search(query: TaskSearchQuery): List<Task> {
        lastSearchQuery = query
        if (failNext) return emptyList()
        return searchResults
    }
}

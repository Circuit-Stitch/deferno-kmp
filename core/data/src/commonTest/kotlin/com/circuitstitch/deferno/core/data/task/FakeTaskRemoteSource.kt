package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId

/**
 * Scriptable [TaskRemoteSource] for the repository tests (#22). A test sets [details] to the full
 * payloads a [hydrate] fetches and [searchResults] to the rows a [search] returns, then asserts how the
 * repository handles them. [failNext] simulates the offline-first failure path. (The cold list snapshot
 * moved to [com.circuitstitch.deferno.core.data.item.ItemSnapshotSource] — #226 — so it is no longer
 * scripted here; see [com.circuitstitch.deferno.core.data.item.FakeItemSnapshotSource].)
 */
class FakeTaskRemoteSource(
    var details: Map<TaskId, Task> = emptyMap(),
    var failNext: Boolean = false,
    /** Hits the next [search] returns (in server order); the repo applies the client sort over these. */
    var searchResults: List<SearchHit> = emptyList(),
) : TaskRemoteSource {

    /** The [TaskSearchQuery] the last [search] received, for asserting the filters reached the wire. */
    var lastSearchQuery: TaskSearchQuery? = null
        private set

    override suspend fun fetch(id: TaskId): Task? {
        if (failNext) return null
        return details[id]
    }

    override suspend fun search(query: TaskSearchQuery): TaskSearchResult {
        lastSearchQuery = query
        if (failNext) return TaskSearchResult.Unavailable
        return TaskSearchResult.Success(searchResults)
    }
}

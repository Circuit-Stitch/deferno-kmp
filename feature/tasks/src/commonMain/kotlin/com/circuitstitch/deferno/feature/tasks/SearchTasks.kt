package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.data.task.TaskSearchResult

/**
 * The narrow read seam the global-search overlay drives (#73): a single online-only, one-shot pull.
 * It is deliberately smaller than the whole [TaskRepository] — the [SearchComponent] needs exactly one
 * verb, "search for these results" — so it depends on this interface, not the repository directly,
 * keeping the component testable on a recording fake. The shell adapts it from the Account's
 * [TaskRepository.search] (ADR-0001/0014).
 */
fun interface SearchTasks {

    /** Run [query]: the matching Tasks, or [TaskSearchResult.Unavailable] on a failed pull. */
    suspend fun search(query: TaskSearchQuery): TaskSearchResult

    companion object {
        /** Adapt a [TaskRepository]'s one-shot [TaskRepository.search] into the seam. */
        fun of(repository: TaskRepository): SearchTasks = SearchTasks { repository.search(it) }
    }
}

package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.model.SearchHit

/**
 * The narrow read seam the global-search overlay drives (#73, #311): a single offline, one-shot local
 * read. It is deliberately smaller than the whole [TaskRepository] — the [SearchComponent] needs exactly
 * one verb, "search for these results" — so it depends on this interface, not the repository directly,
 * keeping the component testable on a recording fake. The shell adapts it from the Account's
 * [TaskRepository.search] (ADR-0001/0014, ADR-0042).
 */
fun interface SearchTasks {

    /** Run [query]: the matching hits across all kinds (empty when nothing matches). */
    suspend fun search(query: TaskSearchQuery): List<SearchHit>

    companion object {
        /** Adapt a [TaskRepository]'s one-shot [TaskRepository.search] into the seam. */
        fun of(repository: TaskRepository): SearchTasks = SearchTasks { repository.search(it) }
    }
}

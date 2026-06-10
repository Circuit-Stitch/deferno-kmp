package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.data.task.MIN_SEARCH_QUERY_LENGTH
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.data.task.TaskSearchResult
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * Observable state for the global-search overlay (#73). [hasSearched] distinguishes the initial,
 * untouched overlay ("type to search") from a completed search that found nothing ("no matches"), so
 * the View can show the right gentle copy; [searchFailed] further distinguishes a search that
 * **couldn't run** ([TaskSearchResult.Unavailable] — offline or a server error) from one that ran and
 * found nothing, so a broken backend never masquerades as "no matches". [results] is the last
 * completed search's rows; [isSearching] is true only while a pull is in flight. The filters
 * ([statuses]/[labels]/[fromDate]/[toDate]) and [sort] mirror [TaskSearchQuery] so the View binds
 * straight to them.
 */
data class SearchState(
    val query: String = "",
    val statuses: Set<WorkingState> = emptySet(),
    val labels: Set<String> = emptySet(),
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    val sort: SearchSort = SearchSort.Relevance,
    val results: List<Task> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    val searchFailed: Boolean = false,
) {
    /** Whether the current query meets the backend's 2-char minimum (#73). */
    val canSearch: Boolean get() = query.trim().length >= MIN_SEARCH_QUERY_LENGTH
}

/**
 * The global-search component (#73, ADR-0007): the shared, Compose-free logic behind the search
 * overlay route. It is **online-only and one-shot** — it drives [TaskRepository.search] (a suspend
 * pull, NOT the offline observed `Flow`), so search results are never confused with the live task list
 * (ADR-0001). Selecting a result emits an [Output.OpenTask] intent the shell routes into the Tasks
 * Destination; [onDismiss] emits [Output.Dismissed] for the shell to pop the overlay.
 *
 * The 2-char minimum guard lives here ([SearchState.canSearch]) so every platform binding inherits it.
 */
interface SearchComponent {
    val state: StateFlow<SearchState>

    fun onQueryChanged(query: String)
    fun onStatusToggled(status: WorkingState)
    fun onLabelToggled(label: String)
    fun onSortChanged(sort: SearchSort)
    fun onDateRangeChanged(from: LocalDate?, to: LocalDate?)

    /** Run the search for the current query + filters (a no-op if the query is below the 2-char floor). */
    fun onSubmit()

    fun onResultClicked(id: TaskId)
    fun onDismiss()

    sealed interface Output {
        /** A result row was tapped — open it in the Tasks Destination (the shell routes + dismisses). */
        data class OpenTask(val id: TaskId) : Output

        /** The overlay was dismissed (the shell pops it back to origin). */
        data object Dismissed : Output
    }
}

class DefaultSearchComponent(
    componentContext: ComponentContext,
    private val searchTasks: SearchTasks,
    private val output: (SearchComponent.Output) -> Unit,
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : SearchComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val _state = MutableStateFlow(SearchState())
    override val state: StateFlow<SearchState> = _state.asStateFlow()

    override fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    override fun onStatusToggled(status: WorkingState) {
        _state.update { it.copy(statuses = it.statuses.toggle(status)) }
    }

    override fun onLabelToggled(label: String) {
        _state.update { it.copy(labels = it.labels.toggle(label)) }
    }

    override fun onSortChanged(sort: SearchSort) {
        // Re-sorting is a client concern; if results are already shown, re-run so the order updates.
        val shouldRerun = _state.value.hasSearched && _state.value.canSearch
        _state.update { it.copy(sort = sort) }
        if (shouldRerun) onSubmit()
    }

    override fun onDateRangeChanged(from: LocalDate?, to: LocalDate?) {
        _state.update { it.copy(fromDate = from, toDate = to) }
    }

    override fun onSubmit() {
        val current = _state.value
        if (!current.canSearch || current.isSearching) return
        _state.update { it.copy(isSearching = true) }
        scope.launch {
            val outcome = searchTasks.search(
                TaskSearchQuery(
                    query = current.query.trim(),
                    statuses = current.statuses,
                    labels = current.labels,
                    fromDate = current.fromDate,
                    toDate = current.toDate,
                    sort = current.sort,
                ),
            )
            _state.update {
                when (outcome) {
                    is TaskSearchResult.Success -> it.copy(
                        results = outcome.tasks,
                        isSearching = false,
                        hasSearched = true,
                        searchFailed = false,
                    )
                    TaskSearchResult.Unavailable -> it.copy(
                        results = emptyList(),
                        isSearching = false,
                        hasSearched = true,
                        searchFailed = true,
                    )
                }
            }
        }
    }

    override fun onResultClicked(id: TaskId) {
        output(SearchComponent.Output.OpenTask(id))
    }

    override fun onDismiss() {
        output(SearchComponent.Output.Dismissed)
    }

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}

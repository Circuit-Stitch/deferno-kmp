package com.circuitstitch.deferno.feature.tasks

import com.arkivanov.decompose.ComponentContext
import com.circuitstitch.deferno.core.common.componentScope
import com.circuitstitch.deferno.core.data.task.SearchSeed
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.data.task.hasRunnableConstraint
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.SearchHit
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
 * Observable state for the global-search overlay (#73, #311). [hasSearched] distinguishes the initial,
 * untouched overlay ("type to search") from a completed search that found nothing ("no matches"), so the
 * View can show the right gentle copy. [results] is the last completed search's rows; [isSearching] is
 * true only while the local read runs. The filters ([statuses]/[labels]/[fromDate]/[toDate]/[hasAttachment])
 * and [sort] mirror [TaskSearchQuery] so the View binds straight to them.
 */
data class SearchState(
    val query: String = "",
    val statuses: Set<WorkingState> = emptySet(),
    val labels: Set<String> = emptySet(),
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
    // The "has attachment" filter (#311): when on, only items with backend-hosted attachments match.
    val hasAttachment: Boolean = false,
    val sort: SearchSort = SearchSort.Relevance,
    val results: List<SearchHit> = emptyList(),
    val isSearching: Boolean = false,
    val hasSearched: Boolean = false,
    // The Active Account's session has expired (#297) — a process-wide flag the overlay still surfaces so
    // the person knows to re-auth, even though offline search itself no longer needs the network.
    val sessionExpired: Boolean = false,
) {
    /**
     * Whether there is something to search on (#73, #311) — delegates to [TaskSearchQuery.hasRunnableConstraint]
     * (the shared predicate [TaskRepository.search] also gates on) so the UI guard can't drift from the
     * repository: a free-text term of at least the 2-char minimum, OR any structured filter
     * (status / label / date range / "has attachment" — the deep-link runs with no text, just the filter + sort).
     */
    val canSearch: Boolean get() = toQuery().hasRunnableConstraint()

    /** This state as the equivalent [TaskSearchQuery] — the single shape the guard + the search submit share. */
    fun toQuery(): TaskSearchQuery = TaskSearchQuery(
        query = query.trim(),
        statuses = statuses,
        labels = labels,
        fromDate = fromDate,
        toDate = toDate,
        hasAttachment = hasAttachment,
        sort = sort,
    )
}

/**
 * The global-search component (#73, #311, ADR-0042): the shared, Compose-free logic behind the search
 * overlay route. It is **offline and one-shot** — it drives [TaskRepository.search] (a suspend local read
 * over the cache, NOT the observed `Flow`), so search results are never confused with the live task list
 * (ADR-0001). Selecting a result emits an [Output.OpenTask] intent the shell routes into the Tasks
 * Destination; [onDismiss] emits [Output.Dismissed] for the shell to pop the overlay.
 *
 * The search guard lives here ([SearchState.canSearch]) so every platform binding inherits it. Opening
 * with a [SearchSeed] (a deep-link, e.g. Settings → Storage "biggest attachments") pre-applies the
 * filter/sort and runs the search immediately.
 */
interface SearchComponent {
    val state: StateFlow<SearchState>

    fun onQueryChanged(query: String)
    fun onStatusToggled(status: WorkingState)
    fun onLabelToggled(label: String)

    /** Toggle the "has attachment" filter (#311), then re-run if results are already shown. */
    fun onHasAttachmentToggled()

    fun onSortChanged(sort: SearchSort)
    fun onDateRangeChanged(from: LocalDate?, to: LocalDate?)

    /** Run the search for the current query + filters (a no-op when [SearchState.canSearch] is false). */
    fun onSubmit()

    /**
     * A result row was tapped (#231). Kind-aware: a Task hit emits [Output.OpenTask] (the Tasks
     * Destination has a Task detail); a non-Task hit is a no-op for now, since habit/chore/event have no
     * v1 detail screen — mirrors the Tasks tree, which only opens Task rows.
     */
    fun onResultClicked(hit: SearchHit)
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
    // A deep-link's pre-applied filter/sort (#311): when present, the overlay opens with it and runs the
    // search immediately (e.g. Settings → Storage "biggest attachments"). Null = the plain search overlay.
    initialSeed: SearchSeed? = null,
    // The process-wide session-expiry flag (#297), folded into the state so the overlay can surface the
    // re-auth prompt. Defaulted to "not expired" so existing tests build without supplying it.
    sessionExpired: StateFlow<Boolean> = MutableStateFlow(false),
    coroutineContext: CoroutineContext = Dispatchers.Default,
) : SearchComponent, ComponentContext by componentContext {

    private val scope = componentScope(coroutineContext)
    private val _state = MutableStateFlow(
        initialSeed?.let { SearchState(hasAttachment = it.hasAttachment, sort = it.sort) } ?: SearchState(),
    )
    override val state: StateFlow<SearchState> = _state.asStateFlow()

    init {
        // Mirror the process-wide session-expiry flag into the state (#297). Folding it in here keeps
        // `state.value` a synchronous read of `_state`.
        scope.launch { sessionExpired.collect { expired -> _state.update { it.copy(sessionExpired = expired) } } }
        // A seeded overlay lands on results, not an empty box (#311) — run the seeded filter/sort at once.
        if (initialSeed != null) onSubmit()
    }

    override fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
    }

    override fun onStatusToggled(status: WorkingState) {
        _state.update { it.copy(statuses = it.statuses.toggle(status)) }
    }

    override fun onLabelToggled(label: String) {
        _state.update { it.copy(labels = it.labels.toggle(label)) }
    }

    override fun onHasAttachmentToggled() {
        val shouldRerun = _state.value.hasSearched
        _state.update { it.copy(hasAttachment = !it.hasAttachment) }
        // Re-run if results are already shown and the toggle still leaves something to search on.
        if (shouldRerun && _state.value.canSearch) onSubmit()
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
            // Offline local read (ADR-0042) — it can't fail, so the result is just the hits.
            val hits = searchTasks.search(current.toQuery())
            _state.update {
                it.copy(results = hits, isSearching = false, hasSearched = true)
            }
        }
    }

    override fun onResultClicked(hit: SearchHit) {
        // Only Task hits have a v1 detail screen; a non-Task tap is a calm no-op (mirrors the tree).
        if (hit.kind == ItemKind.Task) output(SearchComponent.Output.OpenTask(TaskId(hit.id)))
    }

    override fun onDismiss() {
        output(SearchComponent.Output.Dismissed)
    }

    private fun <T> Set<T>.toggle(value: T): Set<T> = if (value in this) this - value else this + value
}

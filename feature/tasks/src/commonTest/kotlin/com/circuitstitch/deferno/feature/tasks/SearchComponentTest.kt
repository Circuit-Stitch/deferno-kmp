package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.data.task.TaskSearchQuery
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The global-search component (#73): query/filter/sort propagation into [TaskSearchQuery], results
 * landing in [SearchState], the open-task + dismiss intents, the 2-char minimum guard, and the
 * isSearching transition — over a recording [SearchTasks] on the JVM-fast path (ADR-0006).
 */
@OptIn(ExperimentalCoroutinesApi::class) // advanceUntilIdle() — drives the scheduler past the init fetch.
class SearchComponentTest {

    private class RecordingSearch(var results: List<com.circuitstitch.deferno.core.model.Task> = emptyList()) :
        SearchTasks {
        val queries = mutableListOf<TaskSearchQuery>()
        override suspend fun search(query: TaskSearchQuery): List<com.circuitstitch.deferno.core.model.Task> {
            queries += query
            return results
        }
    }

    private fun TestScope.component(
        search: SearchTasks,
        output: (SearchComponent.Output) -> Unit = {},
    ) = DefaultSearchComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        searchTasks = search,
        output = output,
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun submitSearchesWithTheQueryAndFiltersAndLandsResults() = runTest {
        val search = RecordingSearch(results = listOf(task("a", title = "Spring launch")))
        val component = component(search)

        component.onQueryChanged("spring")
        component.onStatusToggled(WorkingState.InProgress)
        component.onSubmit()
        advanceUntilIdle()

        assertEquals(1, search.queries.size)
        assertEquals("spring", search.queries.single().query)
        assertEquals(setOf(WorkingState.InProgress), search.queries.single().statuses)
        assertEquals(listOf(TaskId("a")), component.state.value.results.map { it.id })
        assertTrue(component.state.value.hasSearched)
        assertFalse(component.state.value.isSearching)
    }

    @Test
    fun submitCarriesTheChosenDateRangeAndLabelsIntoTheQuery() = runTest {
        // #73 follow-up DEFECT 3: the search query must carry the date range + tags filters the overlay
        // collects (not just status + sort). Drives the date-range + label handlers, then asserts both
        // land in the TaskSearchQuery the seam receives.
        val search = RecordingSearch(results = emptyList())
        val component = component(search)

        component.onQueryChanged("spring")
        component.onLabelToggled("home")
        component.onLabelToggled("errands")
        component.onDateRangeChanged(LocalDate(2026, 6, 1), LocalDate(2026, 6, 30))
        component.onSubmit()
        advanceUntilIdle()

        val query = search.queries.single()
        assertEquals(setOf("home", "errands"), query.labels)
        assertEquals(LocalDate(2026, 6, 1), query.fromDate)
        assertEquals(LocalDate(2026, 6, 30), query.toDate)
    }

    @Test
    fun toggleAddsThenRemovesALabel() = runTest {
        // Re-toggling a label removes it — the chip is a toggle, so the query never carries a stale tag.
        val search = RecordingSearch()
        val component = component(search)

        component.onLabelToggled("home")
        assertEquals(setOf("home"), component.state.value.labels)
        component.onLabelToggled("home")
        assertEquals(emptySet(), component.state.value.labels)
    }

    @Test
    fun aQueryBelowTheTwoCharFloorDoesNotSearch() = runTest {
        val search = RecordingSearch(results = listOf(task("a")))
        val component = component(search)

        component.onQueryChanged("a")
        assertFalse(component.state.value.canSearch, "a 1-char query can't search")
        component.onSubmit()
        advanceUntilIdle()

        assertTrue(search.queries.isEmpty(), "no search reaches the seam")
        assertFalse(component.state.value.hasSearched)
    }

    @Test
    fun resultTapEmitsTheOpenTaskIntent() = runTest {
        val outputs = mutableListOf<SearchComponent.Output>()
        val component = component(RecordingSearch(), outputs::add)

        component.onResultClicked(TaskId("x"))

        assertEquals(listOf<SearchComponent.Output>(SearchComponent.Output.OpenTask(TaskId("x"))), outputs)
    }

    @Test
    fun dismissEmitsTheDismissedIntent() = runTest {
        val outputs = mutableListOf<SearchComponent.Output>()
        val component = component(RecordingSearch(), outputs::add)

        component.onDismiss()

        assertEquals(listOf<SearchComponent.Output>(SearchComponent.Output.Dismissed), outputs)
    }

    @Test
    fun isSearchingFlipsTrueWhileTheSearchIsInFlightThenFalse() = runTest {
        val search = RecordingSearch(results = listOf(task("a")))
        val component = component(search)
        component.onQueryChanged("spring")

        component.state.test {
            assertFalse(awaitItem().isSearching) // initial
            component.onSubmit()
            assertTrue(awaitItem().isSearching, "isSearching is true while the pull runs")
            advanceUntilIdle()
            val settled = awaitItem()
            assertFalse(settled.isSearching, "isSearching clears when results land")
            assertEquals(listOf(TaskId("a")), settled.results.map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun changingTheSortReRunsAnAlreadyCompletedSearch() = runTest {
        val search = RecordingSearch(results = listOf(task("a")))
        val component = component(search)
        component.onQueryChanged("spring")
        component.onSubmit()
        advanceUntilIdle()
        assertEquals(1, search.queries.size)

        component.onSortChanged(SearchSort.TitleAsc)
        advanceUntilIdle()

        // A second search ran with the new sort, so the order updates without a manual re-submit.
        assertEquals(2, search.queries.size)
        assertEquals(SearchSort.TitleAsc, search.queries.last().sort)
    }
}

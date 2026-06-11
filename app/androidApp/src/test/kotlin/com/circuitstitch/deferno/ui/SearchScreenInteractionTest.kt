package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchState
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
import kotlinx.datetime.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests for the global Search overlay View (#73), on the JVM via Robolectric.
 * They assert the thin View forwards the right intents to its [com.circuitstitch.deferno.feature.tasks.SearchComponent]
 * and renders the gentle empty / no-results copy (design-principles.md); the search logic itself is
 * tested in the slice's commonTest (`SearchComponentTest`).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class SearchScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    @Test
    fun typingForwardsTheQueryChange() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Search tasks").performTextInput("spring")

        assertEquals(listOf("spring"), component.queryChanges)
    }

    @Test
    fun statusChipForwardsTheToggle() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("In progress").performClick()

        assertEquals(listOf(WorkingState.InProgress), component.statusToggles)
    }

    @Test
    fun sortChipForwardsTheSortChange() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Title (A–Z)").performClick()

        assertEquals(listOf(SearchSort.TitleAsc), component.sortChanges)
    }

    @Test
    fun addingATagForwardsTheLabelToggle() {
        // #73 follow-up DEFECT 3: the overlay must offer a tags/label control wired to onLabelToggled —
        // it was a dead handler the View never called.
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Add a tag").performTextInput("home")
        composeRule.onNodeWithText("Add tag").performClick()

        assertEquals(listOf("home"), component.labelToggles)
    }

    @Test
    fun tappingASelectedTagChipForwardsTheToggleToRemoveIt() {
        // A selected tag renders as a chip; tapping it toggles it back off (removing it from the query).
        val component = FakeSearchComponent(SearchState(labels = setOf("errands")))
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("errands").performClick()

        assertEquals(listOf("errands"), component.labelToggles)
    }

    @Test
    fun enteringADateRangeForwardsTheDateRangeChange() {
        // #73 follow-up DEFECT 3: the overlay must offer a date-range (from/to) control wired to
        // onDateRangeChanged — it was a dead handler the View never called.
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("From (YYYY-MM-DD)").performTextInput("2026-06-01")
        composeRule.onNodeWithText("To (YYYY-MM-DD)").performTextInput("2026-06-30")

        // Both edits forward the current (from, to) pair; the last carries both parsed dates.
        assertEquals(LocalDate(2026, 6, 1) to null, component.dateRangeChanges.first())
        assertEquals(LocalDate(2026, 6, 1) to LocalDate(2026, 6, 30), component.dateRangeChanges.last())
    }

    @Test
    fun resultRowForwardsTheOpenIntent() {
        val results = listOf(sampleTask("3", "Reply to Sam"))
        val component = FakeSearchComponent(SearchState(query = "reply", results = results, hasSearched = true))
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Reply to Sam").performClick()

        assertEquals(listOf(TaskId("3")), component.resultClicks)
    }

    @Test
    fun closeForwardsTheDismissIntent() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Close").performClick()

        assertEquals(1, component.dismissCount)
    }

    @Test
    fun initialOverlay_showsTheGentlePrompt() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Search your tasks").assertIsDisplayed()
    }

    @Test
    fun completedSearchWithNoResults_showsTheNoMatchesCopy() {
        val component = FakeSearchComponent(SearchState(query = "zzz", results = emptyList(), hasSearched = true))
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("No matches").assertIsDisplayed()
    }

    @Test
    fun failedSearch_showsTheUnavailableCopy_notNoMatches() {
        // A 503/offline pull must not render as "No matches" (#73 follow-up).
        val component = FakeSearchComponent(SearchState(query = "zzz", hasSearched = true, searchFailed = true))
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Search is unavailable").assertIsDisplayed()
        composeRule.onNodeWithText("No matches").assertDoesNotExist()
    }

    @Test
    fun searchButtonForwardsTheSubmit_andIsGatedOnCanSearch() {
        val component = FakeSearchComponent(SearchState(query = "spring"))
        setContent { SearchScreen(component) }

        // hasText + hasClickAction singles out the button (the screen heading is also "Search").
        val searchButton = composeRule.onNode(hasText("Search") and hasClickAction())
        searchButton.performClick()
        assertEquals(1, component.submitCount)

        // Below the 2-char floor the button is disabled — a click forwards nothing.
        component.setState(SearchState(query = "a"))
        searchButton.performClick()
        assertEquals(1, component.submitCount)
    }
}

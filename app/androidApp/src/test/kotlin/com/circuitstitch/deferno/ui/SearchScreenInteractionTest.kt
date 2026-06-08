package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchState
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
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
}

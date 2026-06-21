package com.circuitstitch.deferno.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.circuitstitch.deferno.core.data.task.SearchSort
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.SearchHit
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchState
import com.circuitstitch.deferno.feature.tasks.ui.SearchScreen
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Compose UI interaction tests for the restyled global Search overlay ("Deep search", #231), on the JVM
 * via Robolectric. They assert the thin View forwards the right intents to its [SearchComponent] — the
 * amber search field, the kind-aware result rows, the back/dismiss, the filter sheet (STATUS/WHEN/LABELS)
 * and the sort affordance — and renders the gentle empty / no-match copy (design-principles.md). The
 * search logic itself is tested in the slice's commonTest (`SearchComponentTest`).
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class SearchScreenInteractionTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun setContent(content: @Composable () -> Unit) {
        composeRule.setContent { DefernoTheme { content() } }
    }

    private fun hit(id: String, title: String, kind: ItemKind = ItemKind.Task) =
        SearchHit(id = id, kind = kind, title = title)

    @Test
    fun typingForwardsTheQueryChange() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Search all your trees…").performTextInput("spring")

        assertEquals(listOf("spring"), component.queryChanges)
    }

    @Test
    fun backForwardsTheDismissIntent() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithContentDescription("Back").performClick()

        assertEquals(1, component.dismissCount)
    }

    @Test
    fun resultRowForwardsTheOpenIntent() {
        val component = FakeSearchComponent(
            SearchState(query = "reply", results = listOf(hit("3", "Reply to Sam")), hasSearched = true),
        )
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Reply to Sam").performClick()

        assertEquals(listOf("3"), component.resultClicks.map { it.id })
    }

    @Test
    fun initialOverlay_showsTheGentlePrompt() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Search your trees").assertIsDisplayed()
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
    fun openingFiltersShowsTheSheetSections() {
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Filters").performClick()

        composeRule.onNodeWithText("STATUS").assertIsDisplayed()
        composeRule.onNodeWithText("WHEN").assertIsDisplayed()
        composeRule.onNodeWithText("LABELS").assertIsDisplayed()
    }

    @Test
    fun statusSegmentForwardsTheToggles() {
        // The Active/Done/All segment maps onto the WorkingState set: picking "Done" toggles on the two
        // terminal states (the View keeps the component's single-toggle API).
        val component = FakeSearchComponent()
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Filters").performClick()
        composeRule.onNodeWithText("Done").performClick()

        assertEquals(listOf(WorkingState.Done, WorkingState.Dropped), component.statusToggles)
    }

    @Test
    fun applyFiltersForwardsTheSubmit() {
        val component = FakeSearchComponent(SearchState(query = "spring"))
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Filters").performClick()
        composeRule.onNodeWithText("Apply filters").performClick()

        assertEquals(1, component.submitCount)
    }

    @Test
    fun sortAffordanceForwardsTheNextSort() {
        // The "Best match ▾" affordance cycles to the next sort (Relevance → Title (A–Z)).
        val component = FakeSearchComponent(
            SearchState(query = "spring", results = listOf(hit("1", "Spring launch")), hasSearched = true),
        )
        setContent { SearchScreen(component) }

        composeRule.onNodeWithText("Best match").performClick()

        assertEquals(listOf(SearchSort.TitleAsc), component.sortChanges)
    }

    @Test
    fun removingAnActiveLabelChipForwardsTheToggle() {
        val component = FakeSearchComponent(SearchState(query = "spring", labels = setOf("errands")))
        setContent { SearchScreen(component) }

        composeRule.onNodeWithContentDescription("Remove #errands").performClick()

        assertEquals(listOf("errands"), component.labelToggles)
    }
}

package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.feature.tasks.SearchState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The desktop Search-overlay render test (#86, cf. #39) — a Compose-Multiplatform UI test on the
 * JVM-fast path (no device). It drives the stateless [SearchDesktopContent] with fixed [SearchState]s
 * and intent spies, covering the initial prompt, a results list, and the no-matches copy, and asserting
 * the open (result tap) and dismiss (Close) intents are forwarded. The search logic itself is unit-tested
 * in feature:tasks (SearchComponentTest).
 */
@OptIn(ExperimentalTestApi::class)
class SearchDesktopScreenTest {

    private val sampleTask = Task(
        id = TaskId("1"),
        orgSlug = "u-deferno",
        title = "Plan the spring launch",
        workingState = WorkingState.InProgress,
        dateCreated = Instant.parse("2026-01-01T00:00:00Z"),
    )

    @Test
    fun emptyPrompt_showsTypeToSearchCopy() = runComposeUiTest {
        setContent { Themed { Content(SearchState()) } }
        onNodeWithText("Search your tasks").assertExists()
    }

    @Test
    fun results_renderRows_andForwardOpenIntent() = runComposeUiTest {
        val opened = mutableListOf<TaskId>()
        setContent {
            Themed {
                Content(
                    state = SearchState(results = listOf(sampleTask), hasSearched = true),
                    onResultClicked = { opened += it },
                )
            }
        }
        onNodeWithText("Plan the spring launch").assertExists()
        onNodeWithText("Plan the spring launch").performClick()
        assertEquals(listOf(TaskId("1")), opened)
    }

    @Test
    fun noMatches_showsGentleCopy() = runComposeUiTest {
        setContent { Themed { Content(SearchState(query = "zz", hasSearched = true)) } }
        onNodeWithText("No matches").assertExists()
    }

    @Test
    fun close_forwardsDismissIntent() = runComposeUiTest {
        var dismissed = false
        setContent { Themed { Content(SearchState(), onDismiss = { dismissed = true }) } }
        onNodeWithText("Close").performClick()
        assertTrue(dismissed)
    }
}

@Composable
private fun Content(
    state: SearchState,
    onResultClicked: (TaskId) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    SearchDesktopContent(
        state = state,
        onQueryChanged = {},
        onSubmit = {},
        onStatusToggled = {},
        onLabelToggled = {},
        onDateRangeChanged = { _, _ -> },
        onSortChanged = {},
        onResultClicked = onResultClicked,
        onDismiss = onDismiss,
    )
}

@Composable
private fun Themed(content: @Composable () -> Unit) {
    DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
        Surface(modifier = Modifier.fillMaxSize()) { content() }
    }
}

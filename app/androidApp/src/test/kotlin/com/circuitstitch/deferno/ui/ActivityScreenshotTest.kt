package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.ActivityVerb
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.shell.ActivityComponent
import com.circuitstitch.deferno.shell.ActivityFeedRow
import com.circuitstitch.deferno.shell.ActivityFeedState
import com.circuitstitch.deferno.shell.ui.ActivityScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Roborazzi baseline for the Activity ledger feed (#260) — the reverse-chron list of recorded changes,
 * each with its source chip + time, and the empty state. A fake [ActivityComponent] over fixed rows.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class ActivityScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun component(rows: List<ActivityFeedRow>) = object : ActivityComponent {
        override val state: StateFlow<ActivityFeedState> = MutableStateFlow(ActivityFeedState(rows))
    }

    private val rows = listOf(
        row(5, ActivityVerb.Created, "task", "Created a task", ActivitySource.Mobile, "Mobile app", "2026-06-21T09:45:00Z", "t5"),
        row(4, ActivityVerb.UpdatedTask, null, "Updated a task", ActivitySource.Mobile, "Mobile app", "2026-06-21T09:31:00Z", "t4"),
        row(3, ActivityVerb.UpdatedPlan, null, "Updated your plan", ActivitySource.Mobile, "Mobile app", "2026-06-21T08:12:00Z", null),
        row(2, ActivityVerb.UpdatedTask, null, "Updated a task", ActivitySource.Website, "via Website", "2026-06-20T21:05:00Z", "t2"),
        row(1, ActivityVerb.Created, "habit", "Created a habit", ActivitySource.Mcp, "via MCP agent", "2026-06-20T18:40:00Z", "h1"),
    )

    // The View renders the typed fields (summaryInfo/source); the strings mirror them for the bridges.
    private fun row(
        seq: Long,
        verb: ActivityVerb,
        kindToken: String?,
        summary: String,
        source: ActivitySource,
        sourceLabel: String,
        at: String,
        itemId: String?,
    ) = ActivityFeedRow(seq, summary, sourceLabel, Instant.parse(at), itemId, ActivitySummary(verb, kindToken), source)

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
                Surface(modifier = Modifier.fillMaxSize()) { content() }
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun activity_populated_light() = capture("activity_populated_light") { ActivityScreen(component(rows)) }

    @Test
    @Config(qualifiers = "w400dp-h800dp")
    fun activity_empty_light() = capture("activity_empty_light") { ActivityScreen(component(emptyList())) }
}

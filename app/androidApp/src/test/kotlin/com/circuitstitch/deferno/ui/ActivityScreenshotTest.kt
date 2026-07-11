package com.circuitstitch.deferno.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.ActivityVerb
import com.circuitstitch.deferno.core.designsystem.format.LocalToday
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.ActivityField
import com.circuitstitch.deferno.core.model.ActivityFieldChange
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import com.circuitstitch.deferno.shell.ActivityComponent
import com.circuitstitch.deferno.shell.ActivityFeedRow
import com.circuitstitch.deferno.shell.ActivityFeedState
import com.circuitstitch.deferno.shell.ui.ActivityScreen
import com.github.takahirom.roborazzi.captureRoboImage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Instant

/**
 * Roborazzi baseline for the Activity ledger feed (#260) — the reverse-chron list of recorded changes
 * bucketed under TODAY-aware day dividers, each row carrying its source chip, changed-field hint, and
 * time, plus the empty state. A fake [ActivityComponent] over fixed rows; "today" is pinned via
 * [LocalToday] so the divider doesn't drift with the wall clock.
 */
@RunWith(RobolectricTestRunner::class)
@OptIn(ExperimentalTestApi::class)
class ActivityScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val today = LocalDate(2026, 6, 21)

    private fun component(rows: List<ActivityFeedRow>) = object : ActivityComponent {
        override val state: StateFlow<ActivityFeedState> = MutableStateFlow(ActivityFeedState(rows))
        override fun openItem(id: String) = Unit
    }

    private fun titleAndNotes() = listOf(
        ActivityFieldChange(ActivityField.Title, "title", present("Draft the report"), present("Ship the Q3 report")),
        ActivityFieldChange(ActivityField.Description, "description", present("Rough outline"), present("Pulled the numbers from the new dashboard")),
    )

    private fun statusChange() = listOf(
        ActivityFieldChange(ActivityField.Status, "status", present("in-progress"), present("done")),
    )

    private fun present(value: String): ActivityFieldValue = ActivityFieldValue.Present(value)

    private val rows = listOf(
        row(5, ActivityVerb.Created, "task", ActivitySource.Mobile, "2026-06-21T09:45:00Z", "t5"),
        row(4, ActivityVerb.UpdatedTask, null, ActivitySource.Mobile, "2026-06-21T09:31:00Z", "t4", titleAndNotes()),
        row(3, ActivityVerb.UpdatedPlan, null, ActivitySource.Mobile, "2026-06-21T08:12:00Z", null),
        row(2, ActivityVerb.UpdatedTask, null, ActivitySource.Website, "2026-06-20T21:05:00Z", "t2", statusChange()),
        row(1, ActivityVerb.Created, "habit", ActivitySource.Mcp, "2026-06-20T18:40:00Z", "h1"),
    )

    private fun row(
        seq: Long,
        verb: ActivityVerb,
        kindToken: String?,
        source: ActivitySource,
        at: String,
        itemId: String?,
        changes: List<ActivityFieldChange> = emptyList(),
    ) = ActivityFeedRow(seq, Instant.parse(at), itemId, ActivitySummary(verb, kindToken), source, changes)

    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent {
            CompositionLocalProvider(LocalToday provides today) {
                DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
                    Surface(modifier = Modifier.fillMaxSize()) { content() }
                }
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

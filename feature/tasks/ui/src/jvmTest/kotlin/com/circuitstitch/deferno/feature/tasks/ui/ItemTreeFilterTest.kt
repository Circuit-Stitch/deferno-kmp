package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.theme.DefernoPalette
import com.circuitstitch.deferno.core.designsystem.theme.DefernoTheme
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.feature.tasks.ItemRow
import com.circuitstitch.deferno.feature.tasks.MoveMode
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The local segmented filter's three arms (#386) — the Compose half of a defect that shipped on all four
 * platforms. Compose's variant was the worst of the three: "In today" fell into the `else` arm, so it was
 * byte-identical to **All** and the segment *widened* rather than narrowing.
 *
 * The predicate is asserted twice on purpose: once directly on [visibleTreeRows] (every arm, cheap), and
 * once through the real [ItemTreeContent] by clicking the segment a user clicks — the wiring between the
 * two is exactly what was broken, so a pure-function test alone would not have caught it. This one edit
 * covers **Android and desktop**, which render the same content.
 */
@OptIn(ExperimentalTestApi::class)
class ItemTreeFilterTest {

    private fun row(id: String, title: String, kind: ItemKind = ItemKind.Task, terminal: Boolean = false) =
        ItemRow(
            item = Item(id = id, kind = kind, title = title, isTerminal = terminal),
            depth = 0,
            hasChildren = false,
            isExpanded = false,
        )

    /** A planned Task, a Habit firing today, an unplanned Task, and a finished one. */
    private val planned = row("t-1", "Water the plants")
    private val habitFiringToday = row("h-1", "Morning run", kind = ItemKind.Habit)
    private val unplanned = row("t-2", "Someday, maybe")
    private val finished = row("t-3", "Schedule the post", terminal = true)
    private val rows = listOf(planned, habitFiringToday, unplanned, finished)
    private val inToday = setOf("t-1", "h-1")

    // --- the predicate ---

    /**
     * The heart of #386. A Habit with a firing today is non-terminal, so "Active" keeps it *and* keeps
     * everything else non-terminal — which is exactly why the two segments were indistinguishable. They
     * must now disagree.
     */
    @Test
    fun inTodayDiffersFromActiveForARecurringItemWithAFiringToday() {
        val today = visibleTreeRows(rows, filterIndex = 0, inTodayIds = inToday)
        val active = visibleTreeRows(rows, filterIndex = 1, inTodayIds = inToday)

        assertEquals(listOf("t-1", "h-1"), today.map { it.item.id }, "In today = the joined set")
        assertEquals(listOf("t-1", "h-1", "t-2"), active.map { it.item.id }, "Active = every non-terminal row")
        assertEquals(true, today != active, "the two segments are no longer the same predicate")
    }

    @Test
    fun activeHidesTerminalRows() {
        assertEquals(
            listOf("t-1", "h-1", "t-2"),
            visibleTreeRows(rows, filterIndex = 1, inTodayIds = inToday).map { it.item.id },
        )
    }

    @Test
    fun allKeepsEveryRowIncludingTerminals() {
        assertEquals(rows, visibleTreeRows(rows, filterIndex = 2, inTodayIds = inToday))
    }

    /** Guards the regression this issue *is*: segment 0 must never fall through to "everything". */
    @Test
    fun inTodayWithAnEmptyJoinNarrowsToNothingRatherThanWideningToAll() {
        assertEquals(emptyList(), visibleTreeRows(rows, filterIndex = 0, inTodayIds = emptySet()))
    }

    /** An id in the set with no matching row is simply not a row — never a phantom. */
    @Test
    fun anInTodayIdWithNoLoadedRowContributesNothing() {
        assertEquals(
            listOf("t-1"),
            visibleTreeRows(rows, filterIndex = 0, inTodayIds = setOf("t-1", "not-loaded")).map { it.item.id },
        )
    }

    // --- through the real View ---

    @Test
    fun selectingInTodayNarrowsTheRenderedTreeToTheJoinedSet() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = inToday) } }
        onNodeWithText("In today").performClick()

        onNodeWithText("Water the plants").assertIsDisplayed()
        onNodeWithText("Morning run").assertIsDisplayed()
        onNodeWithText("Someday, maybe").assertDoesNotExist()
        onNodeWithText("Schedule the post").assertDoesNotExist()
    }

    @Test
    fun selectingActiveKeepsTheUnplannedRowThatInTodayHid() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = inToday) } }
        onNodeWithText("Active").performClick()

        onNodeWithText("Someday, maybe").assertIsDisplayed()
        onNodeWithText("Schedule the post").assertDoesNotExist()
    }

    /** The default segment is still All — a filter that narrows on first paint would be a surprise. */
    @Test
    fun theTreeStillOpensOnAllWithEveryRowShowing() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }

        onNodeWithText("Someday, maybe").assertIsDisplayed()
        onNodeWithText("Schedule the post").assertIsDisplayed()
    }

    /**
     * An empty "In today" is not "everything here is done" — nothing is done, nothing is simply on today's
     * plan. The segment gets its own body copy rather than the shared filtered one.
     */
    @Test
    fun anEmptyInTodayExplainsItselfInsteadOfClaimingEverythingIsDone() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }
        onNodeWithText("In today").performClick()

        onNodeWithText("Nothing here is on today's plan. Switch to “All” to see everything.").assertIsDisplayed()
    }

    // --- the leaf node's kind, for TalkBack (#386 / the gap #393 closed on Apple) ---

    @Test
    fun aLeafRowNamesItsKindForScreenReaders() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet()) } }

        // The dot is the only place a tree row expresses kind, and it used to clear its semantics
        // outright — so kind was invisible to TalkBack. Three Task leaves + one Habit leaf here.
        assertEquals(1, kindNodes("habit"), "the Habit leaf names its kind")
        assertEquals(3, kindNodes("task"), "and so does every Task leaf")
    }

    /** In move mode the list goes calm: nothing is interactive, so the dots go fully decorative again. */
    @Test
    fun moveModeLeavesTheLeafDotsSilent() = runComposeUiTest {
        setContent { Themed { Tree(inTodayIds = emptySet(), moveMode = MoveMode("t-1", true, true, true, true)) } }

        assertEquals(0, kindNodes("habit"))
        assertEquals(0, kindNodes("task"))
    }

    private fun ComposeUiTest.kindNodes(label: String): Int =
        onAllNodesWithContentDescription(label, useUnmergedTree = true).fetchSemanticsNodes().size

    @Composable
    private fun Tree(inTodayIds: Set<String>, moveMode: MoveMode? = null) {
        ItemTreeContent(
            rows = rows,
            isRefreshing = false,
            onToggleExpand = { _, _ -> },
            onOpenDetail = { _, _ -> },
            onRefresh = {},
            inTodayIds = inTodayIds,
            moveMode = moveMode,
        )
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        DefernoTheme(palette = DefernoPalette.Deferno, darkTheme = false) {
            Surface(modifier = Modifier.fillMaxSize()) { content() }
        }
    }
}

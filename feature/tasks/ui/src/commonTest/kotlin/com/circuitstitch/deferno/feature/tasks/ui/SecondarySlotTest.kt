package com.circuitstitch.deferno.feature.tasks.ui

import com.circuitstitch.deferno.feature.tasks.TaskPane
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The secondary-pane precedence (#67), unit-tested on the JVM fast path (ADR-0006) since the
 * `@Composable` renderers that consume it can't be. The exhaustive table covers every input
 * combination; the named cases pin the behaviour-critical rows the Tasks screens depend on.
 */
class SecondarySlotTest {

    private data class Case(
        val activePane: TaskPane,
        val hasDetail: Boolean,
        val hasTree: Boolean,
        val expected: SecondarySlot,
    )

    @Test
    fun resolvesEveryCombination() {
        // activePane ∈ {List, Detail, Tree} × hasDetail × hasTree = 12 rows. Recency (activePane) wins
        // only when its own slot is open; otherwise the tree slot is preferred over the detail slot.
        val cases = listOf(
            Case(TaskPane.List, hasDetail = false, hasTree = false, expected = SecondarySlot.None),
            Case(TaskPane.List, hasDetail = false, hasTree = true, expected = SecondarySlot.Tree),
            Case(TaskPane.List, hasDetail = true, hasTree = false, expected = SecondarySlot.Detail),
            Case(TaskPane.List, hasDetail = true, hasTree = true, expected = SecondarySlot.Tree),
            Case(TaskPane.Detail, hasDetail = false, hasTree = false, expected = SecondarySlot.None),
            Case(TaskPane.Detail, hasDetail = false, hasTree = true, expected = SecondarySlot.Tree),
            Case(TaskPane.Detail, hasDetail = true, hasTree = false, expected = SecondarySlot.Detail),
            Case(TaskPane.Detail, hasDetail = true, hasTree = true, expected = SecondarySlot.Detail),
            Case(TaskPane.Tree, hasDetail = false, hasTree = false, expected = SecondarySlot.None),
            Case(TaskPane.Tree, hasDetail = false, hasTree = true, expected = SecondarySlot.Tree),
            Case(TaskPane.Tree, hasDetail = true, hasTree = false, expected = SecondarySlot.Detail),
            Case(TaskPane.Tree, hasDetail = true, hasTree = true, expected = SecondarySlot.Tree),
        )

        for (c in cases) {
            assertEquals(
                c.expected,
                resolveSecondarySlot(c.activePane, hasDetail = c.hasDetail, hasTree = c.hasTree),
                "activePane=${c.activePane}, hasDetail=${c.hasDetail}, hasTree=${c.hasTree}",
            )
        }
    }

    @Test
    fun recencyWinsWhenBothSlotsOpen() {
        assertEquals(SecondarySlot.Tree, resolveSecondarySlot(TaskPane.Tree, hasDetail = true, hasTree = true))
        assertEquals(SecondarySlot.Detail, resolveSecondarySlot(TaskPane.Detail, hasDetail = true, hasTree = true))
    }

    @Test
    fun treeDrillInKeepsTreeForegrounded() {
        // The drill-in regression (#29/#67): activePane = Tree must keep the tree foregrounded even when
        // a detail slot is also open — a fixed precedence would wrongly snap to the detail.
        assertEquals(SecondarySlot.Tree, resolveSecondarySlot(TaskPane.Tree, hasDetail = true, hasTree = true))
    }

    @Test
    fun recencyFallsThroughWhenItsSlotIsClosed() {
        // activePane names a slot that isn't open → fall through to whichever slot remains.
        assertEquals(SecondarySlot.Detail, resolveSecondarySlot(TaskPane.Tree, hasDetail = true, hasTree = false))
        assertEquals(SecondarySlot.Tree, resolveSecondarySlot(TaskPane.Detail, hasDetail = false, hasTree = true))
    }

    @Test
    fun noneWhenNothingOpen() {
        for (pane in TaskPane.entries) {
            assertEquals(
                SecondarySlot.None,
                resolveSecondarySlot(pane, hasDetail = false, hasTree = false),
                "pane=$pane with no slots open",
            )
        }
    }
}

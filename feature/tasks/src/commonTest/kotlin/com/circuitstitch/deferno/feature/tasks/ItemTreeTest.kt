package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The cross-kind forest flatten of [buildItemTree] (ADR-0034, #227): roots + orphan promotion, sibling
 * ordering, the depth-2 default fold, and explicit overrides. Pure logic on the ADR-0006 JVM-fast path.
 */
class ItemTreeTest {

    private fun item(
        id: String,
        kind: ItemKind = ItemKind.Task,
        parentId: String? = null,
        sequence: Long? = null,
        descendantDone: Long? = null,
        descendantTotal: Long? = null,
    ) = Item(
        id = id,
        kind = kind,
        title = "title-$id",
        parentId = parentId,
        sequence = sequence,
        descendantDone = descendantDone,
        descendantTotal = descendantTotal,
    )

    /** id -> row, for terse assertions on a flattened forest. */
    private fun List<ItemRow>.byId() = associateBy { it.item.id }

    @Test
    fun nestsChildrenUnderParentsWithDepthAndOrdersSiblingsBySequence() {
        val rows = buildItemTree(
            listOf(
                item("root", sequence = 1),
                item("b", parentId = "root", sequence = 2),
                item("a", parentId = "root", sequence = 1),
            ),
        )

        // root(depth0) then its children in sequence order a(1), b(2) — all at depth 1.
        assertEquals(listOf("root", "a", "b"), rows.map { it.item.id })
        assertEquals(listOf(0, 1, 1), rows.map { it.depth })
        assertTrue(rows.first().hasChildren)
        assertTrue(rows.first().isExpanded)
    }

    @Test
    fun spansAllFourKindsInOneForest() {
        // A Habit parents a Task (cross-kind nesting, ADR-0034 decision 1).
        val rows = buildItemTree(
            listOf(
                item("h", kind = ItemKind.Habit),
                item("t", kind = ItemKind.Task, parentId = "h"),
                item("c", kind = ItemKind.Chore),
                item("e", kind = ItemKind.Event),
            ),
        )

        val byId = rows.byId()
        assertEquals(1, byId.getValue("t").depth)
        assertEquals(ItemKind.Habit, byId.getValue("h").item.kind)
        assertTrue(byId.getValue("h").hasChildren)
        assertEquals(setOf("h", "t", "c", "e"), byId.keys)
    }

    @Test
    fun promotesAnOrphanWhoseParentIsAbsentToRoot() {
        // "child" points at a parent that isn't in the visible set (aged out of the window) -> root.
        val rows = buildItemTree(listOf(item("child", parentId = "absent-parent")))

        val row = rows.single()
        assertEquals(0, row.depth)
        assertFalse(row.hasChildren)
    }

    @Test
    fun autoCollapsesNodesDeeperThanDepthTwo() {
        // A 0..4 chain: depths 0,1,2 expand by default, so the row at depth 3 is emitted (child of the
        // expanded depth-2 node) but is itself collapsed -> the depth-4 node is hidden.
        val rows = buildItemTree(chain("n0", "n1", "n2", "n3", "n4"))

        assertEquals(listOf("n0", "n1", "n2", "n3"), rows.map { it.item.id })
        val n3 = rows.byId().getValue("n3")
        assertTrue(n3.hasChildren)
        assertFalse(n3.isExpanded) // deeper than depth 2 -> auto-collapsed
    }

    @Test
    fun anExplicitExpandOverrideRevealsADeepNodesChildren() {
        val rows = buildItemTree(chain("n0", "n1", "n2", "n3", "n4"), expandedOverrides = mapOf("n3" to true))

        assertEquals(listOf("n0", "n1", "n2", "n3", "n4"), rows.map { it.item.id })
        assertTrue(rows.byId().getValue("n3").isExpanded)
    }

    @Test
    fun anExplicitCollapseOverrideHidesAShallowNodesChildren() {
        val rows = buildItemTree(
            listOf(item("root"), item("kid", parentId = "root")),
            expandedOverrides = mapOf("root" to false),
        )

        val root = rows.single()
        assertEquals("root", root.item.id)
        assertTrue(root.hasChildren)
        assertFalse(root.isExpanded) // override beats the depth-0 default
    }

    @Test
    fun aChildlessLeafReportsNoChildren() {
        val row = buildItemTree(listOf(item("solo"))).single()
        assertFalse(row.hasChildren)
        assertFalse(row.isExpanded)
    }

    /** A parent→child chain (each links to the next via parentId), for depth-fold assertions. */
    private fun chain(vararg ids: String): List<Item> =
        ids.mapIndexed { i, id -> item(id, parentId = ids.getOrNull(i - 1)) }
}

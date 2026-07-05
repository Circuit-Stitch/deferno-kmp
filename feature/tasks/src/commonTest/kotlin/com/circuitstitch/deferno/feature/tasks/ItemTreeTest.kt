package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.BlockedByRef
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
        blocked: Boolean = false,
        isBlocker: Boolean = false,
        blockedBy: List<BlockedByRef> = emptyList(),
    ) = Item(
        id = id,
        kind = kind,
        title = "title-$id",
        parentId = parentId,
        sequence = sequence,
        descendantDone = descendantDone,
        descendantTotal = descendantTotal,
        blocked = blocked,
        isBlocker = isBlocker,
        blockedBy = blockedBy,
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

    @Test
    fun spineCarriesTheCurvyRailFlagsPerGutterColumn() {
        // root → [a, b]; a → [a1]. The rail [ItemRow.spine] (length == depth) carries one flag per gutter
        // column (#231): each ancestor column's "does it continue below?" plus the row's own
        // "do I have a following sibling?" in the last slot.
        val byId = buildItemTree(
            listOf(
                item("root", sequence = 0),
                item("a", parentId = "root", sequence = 0),
                item("b", parentId = "root", sequence = 1),
                item("a1", parentId = "a", sequence = 0),
            ),
        ).byId()

        assertEquals(emptyList(), byId.getValue("root").spine) // a root has no rail
        assertEquals(listOf(true), byId.getValue("a").spine) // a has a following sibling (b) → continues
        assertEquals(listOf(false), byId.getValue("b").spine) // b is last → its rail stops at the elbow
        // a1's ancestor column (a) continues past it to reach b; a1's own column is last → no continuation.
        assertEquals(listOf(true, false), byId.getValue("a1").spine)
    }

    // --- readiness pruning (#290): ready-only hides blocked items + their whole subtree; rails stay clean ---

    @Test
    fun readyOnlyPrunesABlockedNodeAndItsWholeSubtree() {
        // root → [a, blockedParent → grandchild]. `blocked` inherits down the tree server-side, so the
        // whole blocked branch drops as a unit under ready-only (showBlocked = false); `a` is untouched.
        val rows = buildItemTree(
            listOf(
                item("root", sequence = 0),
                item("a", parentId = "root", sequence = 0),
                item("blockedParent", parentId = "root", sequence = 1, blocked = true),
                item("grandchild", parentId = "blockedParent", sequence = 0, blocked = true),
            ),
            showBlocked = false,
        )

        assertEquals(listOf("root", "a"), rows.map { it.item.id })
    }

    @Test
    fun showBlockedRevealsTheBlockedItemsAgain() {
        val items = listOf(
            item("root", sequence = 0),
            item("a", parentId = "root", sequence = 0),
            item("blockedParent", parentId = "root", sequence = 1, blocked = true),
            item("grandchild", parentId = "blockedParent", sequence = 0, blocked = true),
        )

        val ids = buildItemTree(items, showBlocked = true).map { it.item.id }.toSet()
        assertEquals(setOf("root", "a", "blockedParent", "grandchild"), ids)
    }

    @Test
    fun pruningABlockedSubtreeDropsAChildEvenWhenTheChildIsNotItselfMarked() {
        // Defensive: pruning is by subtree, not a per-row post-filter — a blocked parent hides a child the
        // server didn't (re)mark, rather than re-rooting it as a visible orphan.
        val rows = buildItemTree(
            listOf(
                item("blockedParent", sequence = 0, blocked = true),
                item("child", parentId = "blockedParent", sequence = 0, blocked = false),
            ),
            showBlocked = false,
        )

        assertTrue(rows.isEmpty())
    }

    @Test
    fun pruningABlockedLastSiblingLeavesNoDanglingRail() {
        // root → [a, b(blocked)]; ready-only drops b. `a` is now the last visible child, so its rail must
        // STOP at its elbow (spine == [false]) — not continue toward a row that no longer renders. This is
        // the reason the prune happens *inside* the flatten (before sibling rails are computed), #290.
        val rows = buildItemTree(
            listOf(
                item("root", sequence = 0),
                item("a", parentId = "root", sequence = 0),
                item("b", parentId = "root", sequence = 1, blocked = true),
            ),
            showBlocked = false,
        )

        assertEquals(listOf(false), rows.byId().getValue("a").spine)
    }

    /** A parent→child chain (each links to the next via parentId), for depth-fold assertions. */
    private fun chain(vararg ids: String): List<Item> =
        ids.mapIndexed { i, id -> item(id, parentId = ids.getOrNull(i - 1)) }

    // --- moveOptions: the modal-move target computation (ADR-0034 #228) ---

    /** root → a, b, c (in sequence order); for the relative-move geometry. */
    private fun family() = listOf(
        item("root", sequence = 0),
        item("a", parentId = "root", sequence = 0),
        item("b", parentId = "root", sequence = 1),
        item("c", parentId = "root", sequence = 2),
    )

    @Test
    fun moveOptionsForAMiddleChildOffersAllFourDirections() {
        val o = moveOptions(family(), "b")
        assertEquals(MoveTarget("root", 0), o.up) // before a
        assertEquals(MoveTarget("root", 2), o.down) // after c (index in the group excluding b)
        assertEquals(MoveTarget("a", 0), o.indent) // nest under preceding sibling a (a has no children)
        assertEquals(MoveTarget(null, 1), o.outdent) // root level, right after its parent `root`
    }

    @Test
    fun moveOptionsForTheFirstChildGreysUpAndIndent() {
        val o = moveOptions(family(), "a")
        assertEquals(null, o.up, "first child can't move up")
        assertEquals(null, o.indent, "first child has no preceding sibling to nest under")
        assertEquals(MoveTarget("root", 1), o.down)
        assertEquals(MoveTarget(null, 1), o.outdent)
    }

    @Test
    fun moveOptionsForTheLastChildGreysDown() {
        val o = moveOptions(family(), "c")
        assertEquals(null, o.down, "last child can't move down")
        assertEquals(MoveTarget("root", 1), o.up)
        assertEquals(MoveTarget("b", 0), o.indent) // nest under preceding sibling b
    }

    @Test
    fun indentAppendsAfterThePrecedingSiblingsExistingChildren() {
        // a already has a child; indenting b under a lands b AFTER it (position = a's child count).
        val items = listOf(
            item("root", sequence = 0),
            item("a", parentId = "root", sequence = 0),
            item("a1", parentId = "a", sequence = 0),
            item("b", parentId = "root", sequence = 1),
        )
        assertEquals(MoveTarget("a", 1), moveOptions(items, "b").indent)
    }

    @Test
    fun moveOptionsForALoneRootOffersNothing() {
        // A single top-level item: no sibling to reorder against, no parent to outdent from.
        val o = moveOptions(family(), "root")
        assertEquals(null, o.up)
        assertEquals(null, o.down)
        assertEquals(null, o.indent)
        assertEquals(null, o.outdent, "a root item can't outdent")
    }

    @Test
    fun moveOptionsForAnAbsentIdOffersNothing() {
        val o = moveOptions(family(), "ghost")
        assertEquals(MoveOptions(null, null, null, null), o)
    }

    // --- dependency edges (#291): the dependents map + the picker's cycle guard ---

    @Test
    fun dependentTitlesMapsEachBlockerToItsDirectDependents() {
        val items = listOf(
            item("a", isBlocker = true),
            item("b", blocked = true, blockedBy = listOf(BlockedByRef("a"))),
            item("c", blocked = true, blockedBy = listOf(BlockedByRef("a"), BlockedByRef("x"))),
            item("d"),
        )
        assertEquals(
            mapOf("a" to listOf("title-b", "title-c"), "x" to listOf("title-c")),
            dependentTitles(items),
        )
    }

    @Test
    fun dependentClosureWalksTransitivelyAndOnlyDownstream() {
        // a ← b ← c (c depends on b depends on a), d unrelated: everything downstream of a is {b, c};
        // b's own closure is just {c}; a leaf's is empty — its blockers are upstream, never dependents.
        val items = listOf(
            item("a"),
            item("b", blockedBy = listOf(BlockedByRef("a"))),
            item("c", blockedBy = listOf(BlockedByRef("b"))),
            item("d"),
        )
        assertEquals(setOf("b", "c"), dependentClosure(items, "a"))
        assertEquals(setOf("c"), dependentClosure(items, "b"))
        assertEquals(emptySet(), dependentClosure(items, "c"))
        assertEquals(emptySet(), dependentClosure(items, "d"))
    }

    @Test
    fun dependentClosureTerminatesOnDefensiveCycles() {
        // The server prevents cycles, but the walk must stay total on weird cached data (LWW races).
        val items = listOf(
            item("a", blockedBy = listOf(BlockedByRef("b"))),
            item("b", blockedBy = listOf(BlockedByRef("a"))),
        )
        assertEquals(setOf("a", "b"), dependentClosure(items, "a"))
    }
}

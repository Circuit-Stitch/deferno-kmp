package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Item

/**
 * One flattened, depth-indented row of the Tasks [Item tree] (ADR-0034, #227). The tree is rendered as a
 * single `LazyColumn` of these rows; [depth] drives the leading indent. [hasChildren] is whether the node
 * parents anything in the visible forest (a childless leaf's body is inert, ADR-0034 decision 7);
 * [isExpanded] is meaningful only when [hasChildren] — a collapsed parent ([hasChildren] && ![isExpanded])
 * shows the `descendant_done`/`descendant_total` progress badge.
 *
 * [spine] drives the curvy connecting rail ("See the trees", #231): one flag per gutter column (length =
 * [depth]). Column `i < depth-1` is an **ancestor column** — `true` means that ancestor has a following
 * sibling, so a full-height vertical line runs through this row to reach it. The final entry (`depth-1`,
 * this row's own elbow column) is `true` when this row itself has a following sibling, so the rail
 * continues below the elbow. A depth-0 root has an empty [spine] and no rail.
 */
data class ItemRow(
    val item: Item,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
    val spine: List<Boolean> = emptyList(),
)

/**
 * The default fold rule (ADR-0034 decision 4): a node is expanded by default unless it sits **deeper than**
 * this depth — i.e. depths 0..2 auto-expand, depth 3+ auto-collapse. An explicit per-item override in the
 * device-local fold store wins over this default.
 */
internal const val MAX_DEFAULT_EXPANDED_DEPTH = 2

/**
 * Builds the cross-kind Item forest from [items] (the windowed `/items` snapshot, all four kinds) and
 * flattens it to the visible, depth-indented [ItemRow]s a `LazyColumn` renders (ADR-0034, #227). A thin
 * specialization of [foldFlatten] — the Tasks tree's row type over the cross-kind [Item] projection.
 *
 * - **Roots** are items with no parent **or** whose parent is absent from the visible set — an orphan
 *   (e.g. a parent aged out of the done-visibility window) cannot nest under an absent parent, so it
 *   renders at root (ADR-0034 decision 3).
 * - **Sibling order** is by `sequence` (nulls last), then title, then id — a stable order across kinds.
 * - **Fold:** a node is expanded when [expandedOverrides] holds an explicit choice for its id, else by the
 *   depth default ([MAX_DEFAULT_EXPANDED_DEPTH]); only an expanded parent's children are emitted.
 */
fun buildItemTree(items: List<Item>, expandedOverrides: Map<String, Boolean> = emptyMap()): List<ItemRow> =
    foldFlatten(items, expandedOverrides, id = { it.id }, parentId = { it.parentId }, siblingOrder = SIBLING_ORDER) {
        node, depth, spine, hasChildren, isExpanded ->
        ItemRow(node, depth, hasChildren, isExpanded, spine)
    }

private val SIBLING_ORDER: Comparator<Item> =
    compareBy<Item>({ it.sequence == null }, { it.sequence }, { it.title }, { it.id })

/**
 * Where a single modal-move press would send the lifted item — the destination parent ([newParentId],
 * `null` = root) + the insertion [position] among that parent's children (excluding the lifted row),
 * the exact pair the `Move` command / `ItemWriter` consume (ADR-0034 #228).
 */
data class MoveTarget(val newParentId: String?, val position: Int)

/**
 * The four relative moves available to the lifted item in modal move mode (ADR-0034 decisions 5/6, #228) —
 * **↑↓** reorder among siblings and **‹›** outdent / indent. A `null` arm is an **illegal/disabled** move
 * the UI greys out (the client-side "illegal targets prevented" guard): you can't move up past the first
 * sibling, down past the last, indent with no preceding sibling to nest under, or outdent at the root.
 * Each non-null arm is the [MoveTarget] that press issues — relative moves can never target the item's own
 * self/descendant (a sibling is never below you; the parent is above), so no cycle is possible here; the
 * arbitrary-jump "Move to…" picker that needs that guard is a deferred fast-follow.
 */
data class MoveOptions(
    val up: MoveTarget?,
    val down: MoveTarget?,
    val indent: MoveTarget?,
    val outdent: MoveTarget?,
)

/**
 * Computes the [MoveOptions] for the lifted item [liftedId] over the current cross-kind [items] (ADR-0034
 * #228). Mirrors the tree's own sibling grouping (`foldFlatten`): an absent parent collapses to root, and
 * siblings order by [SIBLING_ORDER] — so the indices here match the order the flatten renders. Returns
 * all-`null` (everything disabled) when the lifted id isn't in the visible set.
 *
 * - **up / down** keep the current parent; `position` is the neighbour's index in the group *excluding*
 *   the lifted row (move up → land before the previous sibling; move down → after the next).
 * - **indent** nests under the immediately-preceding sibling, appended after its current children.
 * - **outdent** becomes a sibling of the current parent, inserted right after it among the grandparent's
 *   children.
 */
fun moveOptions(items: List<Item>, liftedId: String): MoveOptions {
    val lifted = items.firstOrNull { it.id == liftedId }
        ?: return MoveOptions(null, null, null, null)
    val visibleIds = items.mapTo(HashSet(items.size)) { it.id }
    fun parentOf(item: Item): String? = item.parentId?.takeIf(visibleIds::contains)
    fun childrenOf(parent: String?): List<Item> = items.filter { parentOf(it) == parent }.sortedWith(SIBLING_ORDER)

    val parent = parentOf(lifted)
    val siblings = childrenOf(parent)
    val i = siblings.indexOfFirst { it.id == liftedId } // index within the full group (incl. the lifted row)
    val lastIndex = siblings.size - 1

    val up = if (i > 0) MoveTarget(parent, i - 1) else null
    val down = if (i in 0 until lastIndex) MoveTarget(parent, i + 1) else null

    // Indent: nest under the immediately-preceding sibling (never a descendant of the lifted row), at its end.
    val indent = if (i > 0) {
        val newParent = siblings[i - 1].id
        MoveTarget(newParent, childrenOf(newParent).size)
    } else {
        null
    }

    // Outdent: hop up to be the current parent's next sibling among the grandparent's children.
    val outdent = parent?.let { parentId ->
        val parentItem = items.first { it.id == parentId }
        val grandParent = parentOf(parentItem)
        val parentIndex = childrenOf(grandParent).indexOfFirst { it.id == parentId }
        MoveTarget(grandParent, parentIndex + 1)
    }

    return MoveOptions(up, down, indent, outdent)
}

/**
 * The lifted item's **current slot** (ADR-0034 decision 8, #230): its present parent ([MoveTarget.newParentId])
 * + its index among that parent's children — the exact `(newParentId, position)` pair an inverse [MoveTarget]
 * restores it to. Captured *before* a move so undo can move it straight back. Mirrors [moveOptions]'s grouping
 * (an absent parent collapses to root, siblings order by [SIBLING_ORDER]); the index is taken over the group
 * **including** the lifted row, which is the insertion index the move write-path inserts it back at (the
 * other siblings shift around the removed row, so the original index restores the original order). Returns
 * `null` when the id isn't in the visible set (nothing to undo).
 */
fun currentSlot(items: List<Item>, liftedId: String): MoveTarget? {
    val lifted = items.firstOrNull { it.id == liftedId } ?: return null
    val visibleIds = items.mapTo(HashSet(items.size)) { it.id }
    val parent = lifted.parentId?.takeIf(visibleIds::contains)
    val siblings = items.filter { (it.parentId?.takeIf(visibleIds::contains)) == parent }.sortedWith(SIBLING_ORDER)
    return MoveTarget(parent, siblings.indexOfFirst { it.id == liftedId })
}

/**
 * The shared fold-flatten every Tasks tree surface routes through (ADR-0034 decision 4, #227): the Tasks
 * Destination tree (over [Item], via [buildItemTree]) and the detail subtask outline (over `Task`, via
 * `DefaultTaskDetailComponent`) build their rows from this one algorithm, so the fold rule (and the
 * orphan/cycle handling) can't drift between them.
 *
 * Groups [nodes] by parent (an absent parent collapses to the root bucket — orphans render at root),
 * orders siblings by [siblingOrder], and emits a [row] per visible node carrying its `depth`,
 * `hasChildren`, and effective `isExpanded` (an explicit [expandedOverrides] choice for the node's [id],
 * else the depth default [MAX_DEFAULT_EXPANDED_DEPTH]); only an expanded parent's children are emitted.
 *
 * Pure and total: a `parentId` cycle (server-prevented, ADR-0034 decision 5) simply leaves its members
 * unreachable from any root, so they are dropped rather than looping — no cycle guard needed.
 */
internal fun <T, R> foldFlatten(
    nodes: List<T>,
    expandedOverrides: Map<String, Boolean>,
    id: (T) -> String,
    parentId: (T) -> String?,
    siblingOrder: Comparator<T>,
    row: (node: T, depth: Int, spine: List<Boolean>, hasChildren: Boolean, isExpanded: Boolean) -> R,
): List<R> {
    val visibleIds = nodes.mapTo(HashSet(nodes.size), id)
    val childrenByParent: Map<String?, List<T>> = nodes
        .groupBy { parentId(it)?.takeIf(visibleIds::contains) } // an absent parent collapses to the null (root) bucket
        .mapValues { (_, kids) -> kids.sortedWith(siblingOrder) }

    val rows = ArrayList<R>(nodes.size)
    // [spine] (length == depth) carries one rail flag per gutter column: each ancestor column's flag is
    // "that ancestor has a following sibling" (draw a full vertical), and a child appends its own
    // "I have a following sibling" flag — so the curvy rail renders correctly at any depth.
    fun emit(node: T, depth: Int, spine: List<Boolean>) {
        val children = childrenByParent[id(node)].orEmpty()
        val hasChildren = children.isNotEmpty()
        val expanded = hasChildren && (expandedOverrides[id(node)] ?: (depth <= MAX_DEFAULT_EXPANDED_DEPTH))
        rows += row(node, depth, spine, hasChildren, expanded)
        if (expanded) {
            val lastIndex = children.lastIndex
            children.forEachIndexed { index, child -> emit(child, depth + 1, spine + (index != lastIndex)) }
        }
    }
    childrenByParent[null].orEmpty().forEach { emit(it, 0, emptyList()) }
    return rows
}

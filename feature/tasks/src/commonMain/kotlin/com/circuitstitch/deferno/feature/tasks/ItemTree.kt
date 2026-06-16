package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.Item

/**
 * One flattened, depth-indented row of the Tasks [Item tree] (ADR-0034, #227). The tree is rendered as a
 * single `LazyColumn` of these rows; [depth] drives the leading indent. [hasChildren] is whether the node
 * parents anything in the visible forest (a childless leaf's body is inert, ADR-0034 decision 7);
 * [isExpanded] is meaningful only when [hasChildren] — a collapsed parent ([hasChildren] && ![isExpanded])
 * shows the `descendant_done`/`descendant_total` progress badge.
 */
data class ItemRow(
    val item: Item,
    val depth: Int,
    val hasChildren: Boolean,
    val isExpanded: Boolean,
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
        node, depth, hasChildren, isExpanded ->
        ItemRow(node, depth, hasChildren, isExpanded)
    }

private val SIBLING_ORDER: Comparator<Item> =
    compareBy<Item>({ it.sequence == null }, { it.sequence }, { it.title }, { it.id })

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
    row: (node: T, depth: Int, hasChildren: Boolean, isExpanded: Boolean) -> R,
): List<R> {
    val visibleIds = nodes.mapTo(HashSet(nodes.size), id)
    val childrenByParent: Map<String?, List<T>> = nodes
        .groupBy { parentId(it)?.takeIf(visibleIds::contains) } // an absent parent collapses to the null (root) bucket
        .mapValues { (_, kids) -> kids.sortedWith(siblingOrder) }

    val rows = ArrayList<R>(nodes.size)
    fun emit(node: T, depth: Int) {
        val children = childrenByParent[id(node)].orEmpty()
        val hasChildren = children.isNotEmpty()
        val expanded = hasChildren && (expandedOverrides[id(node)] ?: (depth <= MAX_DEFAULT_EXPANDED_DEPTH))
        rows += row(node, depth, hasChildren, expanded)
        if (expanded) children.forEach { emit(it, depth + 1) }
    }
    childrenByParent[null].orEmpty().forEach { emit(it, 0) }
    return rows
}

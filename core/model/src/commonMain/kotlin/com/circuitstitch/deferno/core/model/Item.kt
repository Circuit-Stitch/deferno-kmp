package com.circuitstitch.deferno.core.model

/**
 * The common projection across the four [ItemKind]s (CONTEXT.md → "Item") — the cross-kind read model
 * the Tasks [Item tree] (ADR-0034, #226/#227) renders as one forest. [Task]/[Habit]/[Chore]/[Event]
 * are four unrelated domain types with **no supertype**; this is the small shared shape a tree row
 * needs, built from each kind's cached row by the `Item` mappers in `core:data`. Per-kind detail still
 * loads the concrete model on open — this carries only structure plus the fields a collapsed or
 * de-emphasized row shows, not the kind-specific payload.
 *
 * **Ids are raw UUID strings, not kind-typed.** The tree spans kinds, so a child may nest under a
 * parent of *any* kind ([parentId] matches some other row's [id] regardless of kind), and the
 * device-local fold store keys by this id. (The per-kind models wrap the same wire UUID in their own
 * id type; this projection unwraps it to the string the forest + fold store compare on.)
 *
 * [isTerminal] is the de-emphasis signal — a Done/Dropped [Task] ([WorkingState.isTerminal]) or an
 * Archived recurring definition. [descendantDone]/[descendantTotal] are the server-computed subtree
 * counts for a collapsed node's progress badge; the `/items` snapshot computes them on Tasks only, so
 * they are `null` for the recurring kinds.
 */
data class Item(
    val id: String,
    val kind: ItemKind,
    val title: String,
    val parentId: String? = null,
    val sequence: Long? = null,
    val isTerminal: Boolean = false,
    val descendantDone: Long? = null,
    val descendantTotal: Long? = null,
)

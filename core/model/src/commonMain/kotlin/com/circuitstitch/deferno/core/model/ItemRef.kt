package com.circuitstitch.deferno.core.model

/**
 * A kind-carrying address for one item (#383) — the `(kind, id)` pair every kind-neutral seam in this
 * client already keys on, promoted to a type so a navigation intent can carry the kind the row it came
 * from already knew.
 *
 * **Why this exists at all.** [Item] rows span all four kinds, and the tree's open callback has always
 * had both halves — `onOpenDetail(id: String, kind: ItemKind)` — but the kind was discarded one frame
 * later and only a [TaskId] survived. That discard *was* the bug in #383: with the kind gone, the only
 * safe thing the detail slot could do was refuse every non-Task row. Carrying the pair intact is the
 * whole fix; the four gates fall out of it.
 *
 * **[id] is a raw UUID string, deliberately.** [Item.id] is documented raw for the same reason — the
 * forest nests children under parents of *any* kind, so a kind-typed id cannot express a tree edge. The
 * per-kind models wrap the same wire UUID in their own id type; this is the string they all unwrap to.
 *
 * **[taskId] is the only place a [TaskId] is minted from a ref**, and it is `null` for the three
 * recurring kinds by construction. That is not a convenience — it is the guard. A recurring id smuggled
 * down a `TaskId`-typed seam is the silent-loss shape this codebase has been bitten by before: it
 * applies nothing locally and then dies as a 404 the caller reads as success (`AccountSession`'s write
 * seams warn about exactly this). Making the wrong conversion return `null` rather than compile means a
 * caller has to say what it wants to happen.
 */
data class ItemRef(val id: String, val kind: ItemKind) {

    init {
        require(id.isNotBlank()) { "ItemRef id must not be blank" }
    }

    /** Whether this addresses a [Task] — the one kind with a full read/write detail surface. */
    val isTask: Boolean get() = kind == ItemKind.Task

    /** Whether this addresses a recurring definition — a [Habit], [Chore] or [Event]. */
    val isDefinition: Boolean get() = !isTask

    /** The typed Task id, or `null` when this ref names a recurring definition. See the class KDoc. */
    val taskId: TaskId? get() = if (isTask) TaskId(id) else null
}

/** This row's address, kind intact — the value the Item tree hands to a navigation intent. */
fun Item.ref(): ItemRef = ItemRef(id, kind)

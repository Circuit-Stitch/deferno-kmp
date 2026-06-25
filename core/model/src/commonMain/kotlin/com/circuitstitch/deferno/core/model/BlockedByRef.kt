package com.circuitstitch.deferno.core.model

/**
 * One edge of a [Task]'s ordered `blockedBy` list (ADR-0034, #289): a reference to the blocking item,
 * optionally narrowed to a single occurrence of a recurring item.
 *
 * [item] is the blocker's raw UUID (the cross-kind id form [Item.id] uses, since a blocker may be any
 * [ItemKind]). [occurrence] targets a specific dated firing of a recurring blocker — a **later
 * follow-up** (#289): it stays `null` until the occurrence-blocker slice lands and is decoded as a raw
 * ref pass-through until then. The client never re-derives readiness from these edges — the server's
 * [Item.blocked]/[Item.isBlocker] booleans are authoritative.
 */
data class BlockedByRef(
    val item: String,
    val occurrence: String? = null,
)

package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * One entry in an Item's **server-authored history** — the read-only audit log the Task detail's
 * ACTIVITY feed interleaves with user [Comment]s (ADR-0043). The wire shape (`TaskAction`, an
 * externally-tagged union) is condensed at the network boundary (ADR-0011); everything above sees this
 * clean sealed type.
 *
 * Every entry carries the [recordedAt] instant it was applied (the feed's sort key). Peer references
 * ([Moved.toParentId], [Split.childId], …) stay raw id [String]s: they have no title on the wire, so
 * the View resolves one from the local caches and degrades to "another item" when the peer isn't
 * cached — history never fetches to render. There is **no stable server event id**; identity for a
 * cached row is the local surrogate the store assigns.
 *
 * v1 models the Task action vocabulary only ([StatusChanged] carries [WorkingState]); recurring-kind
 * actions are dropped upstream until a kind-aware status decode lands.
 */
sealed interface ItemHistoryEvent {

    val recordedAt: Instant

    /** The item was created. */
    data class Created(override val recordedAt: Instant) : ItemHistoryEvent

    /** Fields were edited; [fields] names them (raw wire field names). */
    data class Updated(override val recordedAt: Instant, val fields: List<String>) : ItemHistoryEvent

    /** The item was reparented/reordered; peers are nullable (a move to/from the root carries none). */
    data class Moved(
        override val recordedAt: Instant,
        val fromParentId: String?,
        val toParentId: String?,
        val position: Int?,
    ) : ItemHistoryEvent

    /** A parent was assigned ([parentId]). */
    data class ParentAssigned(override val recordedAt: Instant, val parentId: String) : ItemHistoryEvent

    /** The item was split, spawning [childId]. */
    data class Split(override val recordedAt: Instant, val childId: String) : ItemHistoryEvent

    /** The item was folded into the sequence chain ahead of [nextTaskId]. */
    data class FoldedInto(override val recordedAt: Instant, val nextTaskId: String) : ItemHistoryEvent

    /** A child ([childId]) was merged into this item. */
    data class MergedChild(override val recordedAt: Instant, val childId: String) : ItemHistoryEvent

    /** This item was merged into its parent. */
    data class MergedIntoParent(override val recordedAt: Instant) : ItemHistoryEvent

    /** The working state changed [from] → [to]. */
    data class StatusChanged(
        override val recordedAt: Instant,
        val from: WorkingState,
        val to: WorkingState,
    ) : ItemHistoryEvent

    /** An additive/unrecognised server action kind — kept in the feed as a bare timestamped entry. */
    data class Unknown(override val recordedAt: Instant) : ItemHistoryEvent
}

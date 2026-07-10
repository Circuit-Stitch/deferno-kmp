package com.circuitstitch.deferno.feature.tasks

import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import kotlin.time.Instant
import com.circuitstitch.deferno.core.model.Comment as CommentModel

/**
 * One row of the Task detail's single chronological **ACTIVITY** feed (ADR-0043): user [Comment]s and
 * server-authored [HistoryEvent]s, merged and sorted by instant. Each carries a **stable [id]** so
 * SwiftUI `ForEach(id:)` and Compose keys can diff rows across emissions.
 *
 * Comments are read+write (own-comment edit/delete is gated by `currentUserId` in the View); history
 * events are read-only. History peer ids stay raw on [ItemHistoryEvent] — the View resolves their titles
 * from the local caches and degrades to "another item"; the feed never fetches to render.
 */
sealed interface ActivityItem {

    /** Stable row identity for `ForEach(id:)` / Compose `key(...)`. */
    val id: String

    /** The chronological merge/sort key. */
    val at: Instant

    /** A user comment — its id is the comment's own (client-minted then rekeyed, or the server id). */
    data class Comment(val comment: CommentModel) : ActivityItem {
        override val id: String get() = comment.id
        override val at: Instant get() = comment.createdAt // editedAt does not reorder the feed
    }

    /**
     * A server-authored history entry — read-only, keyed by its position in the (append-only) history.
     *
     * [peerTitle] is the render-time-resolved title of the event's structural peer (the [Split] child,
     * the [Moved] destination parent, …), looked up from the local item cache by [mergeActivity] and left
     * `null` when the event has no peer or the peer has aged out of the window (the View then shows
     * "another item"). It is resolved once, off the item cache the component already holds — the feed
     * never fetches to render (ADR-0046).
     */
    data class HistoryEvent(
        override val id: String,
        val event: ItemHistoryEvent,
        val peerTitle: String? = null,
    ) : ActivityItem {
        override val at: Instant get() = event.recordedAt
    }
}

/**
 * The structural **peer id** an [ItemHistoryEvent] references — the other item in a split/move/reparent/
 * fold/merge — or `null` for events with no peer (Created, Updated, StatusChanged, MergedIntoParent,
 * Unknown). [mergeActivity] resolves this id to a title off the local item cache; it never fetches. A
 * [ItemHistoryEvent.Moved] prefers its destination ([Moved.toParentId]) and falls back to the origin
 * ([Moved.fromParentId]) so a move to/from the root (both `null`) resolves to nothing.
 */
fun ItemHistoryEvent.peerId(): String? = when (this) {
    is ItemHistoryEvent.Split -> childId
    is ItemHistoryEvent.Moved -> toParentId ?: fromParentId
    is ItemHistoryEvent.ParentAssigned -> parentId
    is ItemHistoryEvent.FoldedInto -> nextTaskId
    is ItemHistoryEvent.MergedChild -> childId
    is ItemHistoryEvent.Created,
    is ItemHistoryEvent.Updated,
    is ItemHistoryEvent.StatusChanged,
    is ItemHistoryEvent.MergedIntoParent,
    is ItemHistoryEvent.Unknown,
    -> null
}

/**
 * Merge a Task's cached [comments] (already non-deleted, oldest-first) and [history] (oldest-first) into
 * one **reverse-chronological** (newest first) [ActivityItem] feed — the Trail (ADR-0046). Kotlin's
 * `sortedByDescending` is stable, so same-instant rows keep their source order (comments before history,
 * history in server-array order) — the ADR's tiebreak.
 *
 * [peerTitle] resolves a history event's structural [peerId] (split child, move destination, …) to a
 * title off the local item cache the caller holds; it returns `null` for an unresolved/aged-out peer (the
 * View then renders "another item"). Resolution happens here, at merge time — the feed never fetches to
 * render.
 *
 * History rows key off their **position**, not the store's `seq`: `ItemHistoryLocalStore.replaceForItem`
 * re-inserts wholesale on every refresh, so the SQLite `seq` churns — but server history is append-only,
 * so an existing event keeps its index across refreshes, making the index the *stabler* surrogate id.
 */
fun mergeActivity(
    comments: List<CommentModel>,
    history: List<ItemHistoryEvent>,
    peerTitle: (peerId: String) -> String? = { null },
): List<ActivityItem> =
    (comments.map { ActivityItem.Comment(it) } +
        history.mapIndexed { index, event ->
            ActivityItem.HistoryEvent(
                id = "history:$index",
                event = event,
                peerTitle = event.peerId()?.let(peerTitle),
            )
        })
        .sortedByDescending { it.at }

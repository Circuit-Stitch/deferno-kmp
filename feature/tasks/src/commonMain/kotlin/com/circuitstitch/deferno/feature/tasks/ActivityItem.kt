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

    /** A server-authored history entry — read-only, keyed by its position in the (append-only) history. */
    data class HistoryEvent(override val id: String, val event: ItemHistoryEvent) : ActivityItem {
        override val at: Instant get() = event.recordedAt
    }
}

/**
 * Merge a Task's cached [comments] (already non-deleted, oldest-first) and [history] (oldest-first) into
 * one chronological [ActivityItem] feed. Kotlin's `sortedBy` is stable, so same-instant rows keep their
 * source order (comments before history, history in server-array order) — the ADR's tiebreak.
 *
 * History rows key off their **position**, not the store's `seq`: `ItemHistoryLocalStore.replaceForItem`
 * re-inserts wholesale on every refresh, so the SQLite `seq` churns — but server history is append-only,
 * so an existing event keeps its index across refreshes, making the index the *stabler* surrogate id.
 */
fun mergeActivity(comments: List<CommentModel>, history: List<ItemHistoryEvent>): List<ActivityItem> =
    (comments.map { ActivityItem.Comment(it) } +
        history.mapIndexed { index, event -> ActivityItem.HistoryEvent("history:$index", event) })
        .sortedBy { it.at }

package com.circuitstitch.deferno.core.data.comment

import app.cash.sqldelight.coroutines.asFlow
import app.cash.sqldelight.coroutines.mapToList
import com.circuitstitch.deferno.core.database.sql.CommentEntity
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * The local source of truth for a Task's comment thread (ADR-0043, #197). The detail observes
 * [observe] as a `Flow` (ADR-0001) and never reads the network for it; writes apply optimistically
 * here and replay through the outbox. Dumb CRUD by design — the outbox-aware *reconcile* (which server
 * rows to upsert, which stale rows to drop, protecting pending ones) lives in the repository, and the
 * outbox is the pending-state oracle (there is no pending column here).
 */
interface CommentLocalStore {

    /** The live thread for [taskId] — not-yet-deleted, oldest-first (the feed order). */
    fun observe(taskId: TaskId): Flow<List<Comment>>

    /** Insert-or-replace one comment (a reconcile upsert or an optimistic post). */
    suspend fun upsert(comment: Comment)

    /** Optimistic edit: swap the body and stamp [editedAt]. */
    suspend fun setBody(commentId: String, body: String, editedAt: Instant)

    /** Optimistic delete: write the [deletedAt] tombstone (dropped from the live thread; kept until reconcile). */
    suspend fun softDelete(commentId: String, deletedAt: Instant)

    /** Re-key a client-minted id to the server id when the create replays (the id-heal path). */
    suspend fun rekey(fromId: String, toId: String)

    /** Hard-remove one comment (undo an optimistic post the server terminally rejected). */
    suspend fun deleteById(commentId: String)

    /** Every cached comment id for [taskId] — the reconcile diffs these against the server thread. */
    suspend fun idsForTask(taskId: TaskId): List<String>

    /** Drop every cached comment (account sign-out cleanup). */
    suspend fun clear()
}

/** The production [CommentLocalStore] over the SQLDelight [DefernoDatabase]; thin row ↔ [Comment] plumbing. */
class SqlDelightCommentLocalStore(
    private val db: DefernoDatabase,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : CommentLocalStore {

    private val queries get() = db.commentEntityQueries

    override fun observe(taskId: TaskId): Flow<List<Comment>> =
        queries.selectByTask(taskId.value)
            .asFlow()
            .mapToList(dispatcher)
            .map { rows -> rows.map { it.toDomain() } }

    override suspend fun upsert(comment: Comment) {
        queries.upsert(
            comment_id = comment.id,
            task_id = comment.taskId.value,
            body = comment.body,
            created_by = comment.createdBy.value,
            created_at = comment.createdAt.toString(),
            edited_at = comment.editedAt?.toString(),
            deleted_at = comment.deletedAt?.toString(),
            is_private = if (comment.isPrivate) 1L else 0L,
        )
    }

    override suspend fun setBody(commentId: String, body: String, editedAt: Instant) {
        queries.setBody(body = body, edited_at = editedAt.toString(), comment_id = commentId)
    }

    override suspend fun softDelete(commentId: String, deletedAt: Instant) {
        queries.softDelete(deleted_at = deletedAt.toString(), comment_id = commentId)
    }

    override suspend fun rekey(fromId: String, toId: String) {
        // Positional: UPDATE ... SET comment_id = <toId> WHERE comment_id = <fromId>.
        queries.rekey(toId, fromId)
    }

    override suspend fun deleteById(commentId: String) {
        queries.deleteById(commentId)
    }

    override suspend fun idsForTask(taskId: TaskId): List<String> =
        queries.selectIdsByTask(taskId.value).executeAsList()

    override suspend fun clear() {
        queries.deleteAll()
    }
}

/** Decodes a stored `commentEntity` row into the domain [Comment]. */
private fun CommentEntity.toDomain(): Comment = Comment(
    id = comment_id,
    taskId = TaskId(task_id),
    body = body,
    createdBy = UserId(created_by),
    createdAt = Instant.parse(created_at),
    editedAt = edited_at?.let(Instant::parse),
    deletedAt = deleted_at?.let(Instant::parse),
    isPrivate = is_private != 0L,
)

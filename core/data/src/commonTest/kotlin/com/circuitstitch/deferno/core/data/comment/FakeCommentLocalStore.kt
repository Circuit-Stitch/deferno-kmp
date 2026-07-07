package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * In-memory [CommentLocalStore] for the comment write-path tests (ADR-0043, ADR-0006 JVM-fast path) — the
 * dumb-CRUD twin of [SqlDelightCommentLocalStore] (proved separately by `CommentLocalStoreTest`). Backed
 * by an ordered map so [observe] emits the live thread oldest-first, and [rekey] preserves insertion order.
 */
class FakeCommentLocalStore : CommentLocalStore {

    private val rows = MutableStateFlow<Map<String, Comment>>(emptyMap())

    /** Direct read of the backing rows (incl. tombstoned) for assertions. */
    val all: List<Comment> get() = rows.value.values.sortedWith(compareBy({ it.createdAt }, { it.id }))

    override fun observe(taskId: TaskId): Flow<List<Comment>> = rows.map { map ->
        map.values.filter { it.taskId == taskId && it.deletedAt == null }.sortedWith(compareBy({ it.createdAt }, { it.id }))
    }

    override suspend fun upsert(comment: Comment) {
        rows.value = rows.value + (comment.id to comment)
    }

    override suspend fun setBody(commentId: String, body: String, editedAt: Instant) {
        rows.value[commentId]?.let { rows.value = rows.value + (commentId to it.copy(body = body, editedAt = editedAt)) }
    }

    override suspend fun softDelete(commentId: String, deletedAt: Instant) {
        rows.value[commentId]?.let { rows.value = rows.value + (commentId to it.copy(deletedAt = deletedAt)) }
    }

    override suspend fun rekey(fromId: String, toId: String) {
        val row = rows.value[fromId] ?: return
        rows.value = rows.value - fromId + (toId to row.copy(id = toId))
    }

    override suspend fun deleteById(commentId: String) {
        rows.value = rows.value - commentId
    }

    override suspend fun idsForTask(taskId: TaskId): List<String> =
        rows.value.values.filter { it.taskId == taskId }.map { it.id }

    override suspend fun clear() {
        rows.value = emptyMap()
    }
}

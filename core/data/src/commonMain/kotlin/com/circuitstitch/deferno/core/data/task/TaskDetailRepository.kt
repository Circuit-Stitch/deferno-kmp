package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId

/** A file the user picked to attach to a Task — its [filename], MIME [contentType], and raw [bytes]. */
class AttachmentUpload(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * The Task detail's **online-only** extras — comments and attachments (the web detail's Activity
 * thread + attachment grid). Deliberately NOT offline-first like [TaskRepository]: these are
 * read-on-open detail data with no local cache, exactly like [TaskRepository.search] (ADR-0001). A
 * write re-fetches the thread rather than optimistically merging — the laziest correct path while the
 * data is online-only; promote to optimistic + cached when offline detail is actually needed.
 *
 * Reads return `null` to mean "couldn't load" (transport/server failure), distinct from an empty
 * list; writes return `true` on success so the caller can re-fetch and surface a failure toast.
 */
interface TaskDetailRepository {
    /** The live, non-deleted comment thread for [taskId], oldest-first; `null` if it couldn't load. */
    suspend fun comments(taskId: TaskId): List<Comment>?

    /** Post a new comment on [taskId]. Returns `true` on success. */
    suspend fun postComment(taskId: TaskId, body: String): Boolean

    /** Edit comment [commentId]'s body. Returns `true` on success. */
    suspend fun editComment(commentId: String, body: String): Boolean

    /** Delete comment [commentId]. Returns `true` on success. */
    suspend fun deleteComment(commentId: String): Boolean

    /** The attachments on [taskId]; `null` if they couldn't load. */
    suspend fun attachments(taskId: TaskId): List<Attachment>?

    /**
     * Upload [files] to [taskId] (presign → byte-exact PUT → commit, mirroring the feedback flow).
     * Returns `true` only if every file committed; the caller re-fetches the list on success.
     */
    suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>): Boolean

    /** Delete attachment [attachmentId] from [taskId]. Returns `true` on success. */
    suspend fun deleteAttachment(taskId: TaskId, attachmentId: String): Boolean

    /**
     * The signed-in user's [UserId] (`GET /auth/me`), used by the detail to decide which comments offer
     * edit/delete affordances; `null` if it couldn't resolve (the server still enforces authorization).
     */
    suspend fun currentUserId(): UserId?

    companion object {
        /** A no-op repository (empty reads, failed writes) — the default the component/shell build over. */
        val NONE: TaskDetailRepository = object : TaskDetailRepository {
            override suspend fun comments(taskId: TaskId): List<Comment> = emptyList()
            override suspend fun postComment(taskId: TaskId, body: String): Boolean = false
            override suspend fun editComment(commentId: String, body: String): Boolean = false
            override suspend fun deleteComment(commentId: String): Boolean = false
            override suspend fun attachments(taskId: TaskId): List<Attachment> = emptyList()
            override suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>): Boolean = false
            override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String): Boolean = false
            override suspend fun currentUserId(): UserId? = null
        }
    }
}

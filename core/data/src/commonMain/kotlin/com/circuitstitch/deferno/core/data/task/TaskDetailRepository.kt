package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.TaskId

/** A file the user picked to attach to a Task — its [filename], MIME [contentType], and raw [bytes]. */
class AttachmentUpload(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * The Task detail's **online-only** attachments (the web detail's attachment grid). Deliberately NOT
 * offline-first like [TaskRepository]: read-on-open detail data with no local cache (ADR-0001). A write
 * re-fetches the list rather than optimistically merging — the laziest correct path while attachments
 * stay online-only. (Comments + item history are now offline-first — ADR-0043 — via `CommentRepository` /
 * `ItemHistoryRepository`; identity is device-local, so this repo no longer serves `/auth/me`.)
 *
 * Reads return `null` to mean "couldn't load" (transport/server failure), distinct from an empty
 * list; writes return `true` on success so the caller can re-fetch and surface a failure toast.
 */
interface TaskDetailRepository {
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
     * Set, change, or clear attachment [attachmentId]'s [caption] (on [taskId]); `null` clears it
     * (#416). Returns `true` on success.
     */
    suspend fun updateAttachmentCaption(taskId: TaskId, attachmentId: String, caption: String?): Boolean

    companion object {
        /** A no-op repository (empty reads, failed writes) — the default the component/shell build over. */
        val NONE: TaskDetailRepository = object : TaskDetailRepository {
            override suspend fun attachments(taskId: TaskId): List<Attachment> = emptyList()
            override suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>): Boolean = false
            override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String): Boolean = false
            override suspend fun updateAttachmentCaption(taskId: TaskId, attachmentId: String, caption: String?): Boolean = false
        }
    }
}

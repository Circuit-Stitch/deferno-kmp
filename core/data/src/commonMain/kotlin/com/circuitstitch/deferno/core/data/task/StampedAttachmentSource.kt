package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.TaskId

/**
 * The **wire half** of the Task detail's attachment surface (#364): the same four calls as
 * [TaskDetailRepository], except every write carries the [ActivityStamp] whose `entryId` is the merge key
 * between the row the server files and the optimistic row [LedgerRecordingTaskDetailRepository] records.
 *
 * It exists so the stamp is not a parameter on the app-facing port. There it was a lie in both directions:
 * no caller has one to pass (the stamp is minted inside the decorator, which then *discarded* whatever it
 * was handed), and every other implementation — the no-op, the component fakes — had to carry an argument
 * it could only ignore. Splitting keeps `stamp` where it is mandatory and meaningful: between the one
 * decorator that mints it and the one adapter that puts it on the wire. Non-null for the same reason — a
 * nullable stamp here would re-open the path that sends an unstamped mutation, whose server-minted id the
 * `?since=` reconcile can never match back to a local row (ADR-0048).
 *
 * [attachments] rides along because the decorator has to serve reads too, and one delegate is simpler than
 * two constructor parameters that must always be handed the same object.
 *
 * `internal`: this and [KtorTaskDetailRepository] are core:data's business. Outside the module the only
 * attachment type is [TaskDetailRepository] — which is the point of the split.
 */
internal interface StampedAttachmentSource {
    /** The attachments on [taskId]; `null` if they couldn't load. */
    suspend fun attachments(taskId: TaskId): List<Attachment>?

    /** Upload [files] to [taskId] (presign → byte-exact PUT → commit); only the commit carries [stamp]. */
    suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>, stamp: ActivityStamp): Boolean

    /** Soft-delete attachment [attachmentId] from [taskId] — a POST, because the body is what carries [stamp]. */
    suspend fun deleteAttachment(taskId: TaskId, attachmentId: String, stamp: ActivityStamp): Boolean

    /** Set, change, or clear attachment [attachmentId]'s [caption] on [taskId]; `null` clears it (#416). */
    suspend fun updateAttachmentCaption(
        taskId: TaskId,
        attachmentId: String,
        caption: String?,
        stamp: ActivityStamp,
    ): Boolean
}

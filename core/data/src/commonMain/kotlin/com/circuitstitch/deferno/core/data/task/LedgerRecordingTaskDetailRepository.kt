package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.activity.ActivityActionKind
import com.circuitstitch.deferno.core.data.activity.ActivityLedgerStore
import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.data.activity.LocalActivityChange
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.TaskId
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * Brings the **online-only** attachment writes into the activity ledger (#364) — the
 * [com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore] pattern applied to the one
 * mutation surface that does not ride the outbox.
 *
 * ## Why these need their own decorator
 *
 * Attachments are deliberately not offline-first (ADR-0001): presign → byte-exact PUT → commit cannot be
 * replayed from a queue, so they never reach `OutboxStore.enqueue` and the outbox choke-point never sees
 * them. Before this, adding or removing an attachment left **no trace in the feed at all** — the one class
 * of item mutation the ledger silently missed.
 *
 * ## Why the stamp is minted here and not in the Ktor layer
 *
 * Same reason as the outbox decorator: the stamp's id is the merge key between the optimistic row written
 * here and the authoritative row the server files, so it must be minted once by whoever writes both. The
 * Ktor repository stays a pure wire adapter and simply carries the stamp it is handed.
 *
 * That is why the delegate is [StampedAttachmentSource] and not [TaskDetailRepository]: the stamp is an
 * output this seam produces, so it belongs on the half below it, not on the app-facing port above — where
 * it would be a parameter no caller can fill and this class would have to discard.
 *
 * ## Failure semantics differ from the outbox — on purpose
 *
 * An outbox write is durable the moment it is enqueued, so its ledger row is written unconditionally. An
 * attachment write is not: it either reaches the server now or never happened. So the row is recorded
 * **only on success**, and a failed upload leaves the feed untouched rather than claiming a change that
 * did not occur. The record itself stays best-effort — a ledger failure must not turn a successful upload
 * into a reported one.
 */
internal class LedgerRecordingTaskDetailRepository(
    private val delegate: StampedAttachmentSource,
    private val ledger: ActivityLedgerStore,
    private val now: () -> Instant = { Clock.System.now() },
    private val mintStamp: (Instant) -> ActivityStamp = ActivityStamp::mint,
) : TaskDetailRepository {

    override suspend fun attachments(taskId: TaskId): List<Attachment>? = delegate.attachments(taskId)

    override suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>): Boolean =
        record(taskId, ActivityActionKind.AttachmentAdded, listOf("items", taskId.value, "attachments")) {
            delegate.uploadAttachments(taskId, files, it)
        }

    override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String): Boolean =
        record(
            taskId,
            ActivityActionKind.AttachmentDeleted,
            listOf("items", taskId.value, "attachments", attachmentId, "delete"),
        ) { delegate.deleteAttachment(taskId, attachmentId, it) }

    override suspend fun updateAttachmentCaption(taskId: TaskId, attachmentId: String, caption: String?): Boolean =
        record(
            taskId,
            ActivityActionKind.AttachmentCaptioned,
            listOf("items", taskId.value, "attachments", attachmentId),
            OutboxMethod.Patch,
        ) { delegate.updateAttachmentCaption(taskId, attachmentId, caption, it) }

    /**
     * Mint a stamp, run [write] with it, and record the ledger row iff the write reported success.
     *
     * The recorded row carries an explicit [kind] rather than letting the read model derive a verb from
     * the target: these paths have no meaningful outbox target (`item:{id}` would read as "Moved an
     * item"), and the server names the same verb on its own row — so naming it here is what makes the two
     * agree before the reconcile arrives to confirm it.
     */
    private suspend fun record(
        taskId: TaskId,
        kind: ActivityActionKind,
        path: List<String>,
        method: OutboxMethod = OutboxMethod.Post,
        write: suspend (ActivityStamp) -> Boolean,
    ): Boolean {
        val at = now()
        val stamp = mintStamp(at)
        if (!write(stamp)) return false
        runCatching {
            ledger.recordLocal(
                LocalActivityChange("task:${taskId.value}", method, path),
                at = at,
                stamp = stamp,
                actionKind = kind,
            )
        }
        return true
    }
}

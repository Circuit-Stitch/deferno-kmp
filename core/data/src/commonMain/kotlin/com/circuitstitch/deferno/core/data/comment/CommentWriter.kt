package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.outbox.DeleteComment
import com.circuitstitch.deferno.core.data.outbox.EditComment
import com.circuitstitch.deferno.core.data.outbox.OutboxStore
import com.circuitstitch.deferno.core.data.outbox.PostComment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlin.time.Clock
import kotlin.time.Instant
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * The offline-first comment write path (ADR-0043, #197) — the comment sibling of
 * [com.circuitstitch.deferno.core.data.settings.OutboxSettingsWriter]. Every write applies **optimistically**
 * to the [CommentLocalStore] (so the observed thread updates with no network round-trip, offline or on)
 * and enqueues its [com.circuitstitch.deferno.core.data.outbox.CommentMutation] on the outbox to replay.
 */
interface CommentWriter {

    /** Post a new comment on [taskId]; mints a client id, inserts the optimistic row, enqueues the create. */
    suspend fun post(taskId: TaskId, body: String)

    /** Edit comment [commentId]'s body optimistically and enqueue the idempotent PATCH. */
    suspend fun edit(commentId: String, body: String)

    /** Tombstone comment [commentId] optimistically and enqueue the idempotent DELETE. */
    suspend fun delete(commentId: String)
}

/**
 * The production [CommentWriter]. [author] is the current signed-in user (device-local — `UserId` of the
 * Active Account, ADR-0043 offline-first invariant, NO live `/auth/me`), stamped on an optimistic post so
 * it renders as "You" with edit/delete affordances the instant it is posted, before it syncs.
 */
@OptIn(ExperimentalUuidApi::class)
class OutboxCommentWriter(
    private val localStore: CommentLocalStore,
    private val outbox: OutboxStore,
    private val author: () -> UserId,
    private val now: () -> Instant = { Clock.System.now() },
    private val newId: () -> String = { Uuid.random().toString() },
) : CommentWriter {

    override suspend fun post(taskId: TaskId, body: String) {
        val id = newId()
        val at = now()
        localStore.upsert(Comment(id = id, taskId = taskId, body = body, createdBy = author(), createdAt = at))
        val mutation = PostComment(taskId, id, body)
        outbox.enqueue(mutation.target, mutation.toRequest(), at)
    }

    override suspend fun edit(commentId: String, body: String) {
        val at = now()
        localStore.setBody(commentId, body, at)
        val mutation = EditComment(commentId, body)
        outbox.enqueue(mutation.target, mutation.toRequest(), at)
    }

    override suspend fun delete(commentId: String) {
        val at = now()
        localStore.softDelete(commentId, at)
        val mutation = DeleteComment(commentId)
        outbox.enqueue(mutation.target, mutation.toRequest(), at)
    }
}

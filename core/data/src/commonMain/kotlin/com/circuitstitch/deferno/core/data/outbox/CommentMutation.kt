package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The offline-first comment write intents (ADR-0043, #197) — the comment analogue of [TaskMutation] /
 * [CreateMutation]. Unlike a [TaskMutation] these don't transform a cached [com.circuitstitch.deferno.core.model.Task];
 * their optimistic effect is on the `commentEntity` cache and lives in
 * [com.circuitstitch.deferno.core.data.comment.OutboxCommentWriter]. All three keep the minimal-body
 * rule (ADR-0011): the request carries only `body`.
 *
 * - **Edit / delete** address an existing comment (`comment:<id>`, [CommentTargets.edit]) and replay
 *   fire-and-forget — both are naturally idempotent (a re-sent `PATCH body` is safe; a `DELETE` on a
 *   gone comment is a `404` the sender maps to success). No client id needed.
 * - **Create** ([PostComment]) is response-bearing (`comment-create:<taskId>:<clientId>`,
 *   [CommentTargets.create]): it POSTs *without* an id (the backend does not accept one), so the client
 *   id is a throwaway local handle the [CommentReplayListener] rekeys to the server id on replay.
 */
sealed interface CommentMutation : Mutation

/** Post a new comment on [taskId] under the throwaway [clientId] (`POST tasks/{id}/comments {body}`). */
data class PostComment(val taskId: TaskId, val clientId: String, val body: String) : CommentMutation {
    override val target: String get() = CommentTargets.create(taskId.value, clientId)
    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Post,
        listOf("tasks", taskId.value, "comments"),
        buildJsonObject { put("body", body) }.toString(),
    )
}

/** Edit comment [commentId]'s body (`PATCH comments/{id} {body}`) — idempotent. */
data class EditComment(val commentId: String, val body: String) : CommentMutation {
    override val target: String get() = CommentTargets.edit(commentId)
    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Patch,
        listOf("comments", commentId),
        buildJsonObject { put("body", body) }.toString(),
    )
}

/** Delete comment [commentId] (`DELETE comments/{id}`, no body) — idempotent (404 = success). */
data class DeleteComment(val commentId: String) : CommentMutation {
    override val target: String get() = CommentTargets.edit(commentId)
    override fun toRequest(): OutboxRequest = OutboxRequest(OutboxMethod.Delete, listOf("comments", commentId))
}

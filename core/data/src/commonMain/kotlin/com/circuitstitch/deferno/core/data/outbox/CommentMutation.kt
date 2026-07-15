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
        // `is_private` is a REQUIRED field of the server's CreateCommentPayload — omitting it 422s the POST
        // (Terminal), which now dead-letters the write instead of syncing it. Sending it keeps the create
        // valid so it lands. ponytail: hardcoded false — this client has no private-comment UI yet; thread
        // it through PostComment when one lands.
        buildJsonObject { put("body", body); put("is_private", false) }.toString(),
    )
}

/**
 * Edit comment [commentId]'s body (`PATCH comments/{id} {body}`) — idempotent. [taskId] is carried only to
 * tag the ledger target (`comment:<taskId>:<commentId>`) so the Activity feed can resolve which item this
 * touched — it is display-only, never sent; `null` when the writer couldn't resolve it (legacy target).
 */
data class EditComment(val taskId: String?, val commentId: String, val body: String) : CommentMutation {
    override val target: String get() = CommentTargets.edit(taskId, commentId)
    override fun toRequest(): OutboxRequest = OutboxRequest(
        OutboxMethod.Patch,
        listOf("comments", commentId),
        buildJsonObject { put("body", body) }.toString(),
    )
}

/**
 * Delete comment [commentId] (`DELETE comments/{id}`, no body) — idempotent (404 = success). [taskId] tags
 * the ledger target only (`comment:<taskId>:<commentId>`), display-only and never sent; `null` when unresolved.
 */
data class DeleteComment(val taskId: String?, val commentId: String) : CommentMutation {
    override val target: String get() = CommentTargets.edit(taskId, commentId)
    override fun toRequest(): OutboxRequest = OutboxRequest(OutboxMethod.Delete, listOf("comments", commentId))
}

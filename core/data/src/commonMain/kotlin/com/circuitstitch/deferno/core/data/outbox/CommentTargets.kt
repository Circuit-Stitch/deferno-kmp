package com.circuitstitch.deferno.core.data.outbox

/**
 * The outbox [target][com.circuitstitch.deferno.core.data.outbox.Mutation.target] scheme for the comment
 * write path (ADR-0043, #197) — the single source of truth both the enqueue side (the comment mutations)
 * and the read side (the refresh's #143 clobber-guard + the processor's create route) key on.
 *
 * - **Edit/delete** address an existing comment: `comment:<id>`. Naturally idempotent (a re-sent `PATCH`
 *   is safe, a `DELETE` on a gone comment is a 404 the sender already maps to success), so no client id.
 * - **Create** is response-bearing: `comment-create:<taskId>:<clientId>`. The backend never honours the
 *   client id (no `id` on `CreateCommentPayload`, Circuit-Stitch/Deferno#559), so [clientId] is a
 *   throwaway local handle rekeyed to the server id on replay — the analogue of `create:<kind>:<id>`,
 *   with `taskId` playing the role `kind` does for item creates.
 *
 * Both ids are UUIDs (no `:`), so the create target's two id segments split cleanly on `:`.
 */
object CommentTargets {

    const val EDIT_PREFIX: String = "comment:"
    const val CREATE_PREFIX: String = "comment-create:"

    /** The `comment:<id>` target for an edit/delete of comment [commentId]. */
    fun edit(commentId: String): String = "$EDIT_PREFIX$commentId"

    /** The `comment-create:<taskId>:<clientId>` target for an offline post. */
    fun create(taskId: String, clientId: String): String = "$CREATE_PREFIX$taskId:$clientId"

    /**
     * The comment id a [target] protects from an on-open refresh (#143): the edit/delete's `<id>` or the
     * create's `<clientId>`. `null` for any non-comment target, so the refresh can filter the outbox in
     * one pass.
     */
    fun protectedId(target: String): String? = when {
        target.startsWith(CREATE_PREFIX) -> target.substringAfterLast(':').ifBlank { null }
        target.startsWith(EDIT_PREFIX) -> target.removePrefix(EDIT_PREFIX).ifBlank { null }
        else -> null
    }

    /** The `(taskId, clientId)` of a `comment-create:` [target]; `null` for any other target. */
    fun parseCreate(target: String): CommentCreate? {
        if (!target.startsWith(CREATE_PREFIX)) return null
        val rest = target.removePrefix(CREATE_PREFIX)
        val taskId = rest.substringBefore(':')
        val clientId = rest.substringAfter(':', "")
        return if (taskId.isBlank() || clientId.isBlank()) null else CommentCreate(taskId, clientId)
    }
}

/** The decoded segments of a `comment-create:<taskId>:<clientId>` outbox target. */
data class CommentCreate(val taskId: String, val clientId: String)

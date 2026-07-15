package com.circuitstitch.deferno.core.data.outbox

/**
 * The outbox [target][com.circuitstitch.deferno.core.data.outbox.Mutation.target] scheme for the comment
 * write path (ADR-0043, #197) — the single source of truth both the enqueue side (the comment mutations)
 * and the read side (the refresh's #143 clobber-guard + the processor's create route) key on.
 *
 * - **Edit/delete** address an existing comment: `comment:<taskId>:<commentId>` (the [taskId] segment
 *   lets the Activity feed resolve which item a "Commented on …" row touched, #260). It is display-only —
 *   never sent, since edit/delete PATCH/DELETE `comments/<commentId>` by id. Naturally idempotent (a
 *   re-sent `PATCH` is safe, a `DELETE` on a gone comment is a 404 the sender already maps to success),
 *   so no client id. Rows enqueued before the taskId segment landed keep the old `comment:<commentId>`
 *   shape; every parser here reads both, so old queued/ledger rows degrade gracefully.
 * - **Create** is response-bearing: `comment-create:<taskId>:<clientId>`. The backend never honours the
 *   client id (no `id` on `CreateCommentPayload`, Circuit-Stitch/Deferno#559), so [clientId] is a
 *   throwaway local handle rekeyed to the server id on replay — the analogue of `create:<kind>:<id>`,
 *   with `taskId` playing the role `kind` does for item creates.
 *
 * All ids are UUIDs (no `:`), so every segment splits cleanly on `:`. The `comment-create:` prefix is not
 * a prefix-match of `comment:`, so the two schemes stay disjoint.
 */
object CommentTargets {

    const val EDIT_PREFIX: String = "comment:"
    const val CREATE_PREFIX: String = "comment-create:"

    /**
     * The edit/delete target for comment [commentId]. When [taskId] is known → `comment:<taskId>:<commentId>`
     * (so the Activity feed can resolve the item); when it can't be resolved → the legacy `comment:<commentId>`.
     */
    fun edit(taskId: String?, commentId: String): String =
        if (taskId.isNullOrBlank()) "$EDIT_PREFIX$commentId" else "$EDIT_PREFIX$taskId:$commentId"

    /** The `comment-create:<taskId>:<clientId>` target for an offline post. */
    fun create(taskId: String, clientId: String): String = "$CREATE_PREFIX$taskId:$clientId"

    /**
     * The comment id a [target] protects from an on-open refresh (#143): the create's `<clientId>` or the
     * edit/delete's `<commentId>` — always the **last** `:`-segment, so it reads both the new
     * `comment:<taskId>:<commentId>` and the legacy `comment:<commentId>` shapes. `null` for any
     * non-comment target, so the refresh can filter the outbox in one pass.
     */
    fun protectedId(target: String): String? = when {
        target.startsWith(CREATE_PREFIX) -> target.substringAfterLast(':').ifBlank { null }
        target.startsWith(EDIT_PREFIX) -> target.substringAfterLast(':').ifBlank { null }
        else -> null
    }

    /**
     * The task id a comment [target] touched — from a `comment-create:<taskId>:…` or a new-shape
     * `comment:<taskId>:<commentId>`. `null` for the legacy `comment:<commentId>` (no taskId segment) or
     * any non-comment target. Used by the Activity feed to resolve a "Commented on …" row's item.
     */
    fun taskId(target: String): String? = when {
        target.startsWith(CREATE_PREFIX) -> parseCreate(target)?.taskId
        target.startsWith(EDIT_PREFIX) -> {
            val parts = target.removePrefix(EDIT_PREFIX).split(":")
            if (parts.size >= 2) parts[0].ifBlank { null } else null
        }
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

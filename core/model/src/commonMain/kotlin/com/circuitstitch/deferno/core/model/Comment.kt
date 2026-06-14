package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * A user comment on a Task — the domain model of `GET/POST /tasks/{id}/comments` and the per-comment
 * `PATCH/DELETE /comments/{id}` (the web detail's "Activity" thread). The wire shape (snake_case keys,
 * RFC3339 timestamp strings, the encrypted-body variant) is condensed at the network boundary
 * (ADR-0011); everything above sees this clean shape.
 *
 * - [id] is the opaque server handle edit/delete address (kept a plain String — it is only ever passed
 *   back to the same repository, never reconciled against a local table, so it earns no value class).
 * - [body] is the plaintext comment. The wire can instead carry an encrypted `body_enc` blob; this v1
 *   client does not do client-side comment encryption, so an encrypted-only comment maps to a `null`
 *   body and renders as a redacted placeholder rather than failing.
 * - [createdBy] is the author's [UserId]; the detail compares it to the current user to decide whether
 *   to offer edit/delete affordances (the server enforces the real authorization).
 * - [editedAt] / [deletedAt] are tombstone/edit markers; a soft-deleted comment is filtered out before
 *   it reaches the UI.
 */
data class Comment(
    val id: String,
    val taskId: TaskId,
    val body: String?,
    val createdBy: UserId,
    val createdAt: Instant,
    val editedAt: Instant? = null,
    val deletedAt: Instant? = null,
    val isPrivate: Boolean = false,
)

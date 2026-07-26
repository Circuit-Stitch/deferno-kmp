package com.circuitstitch.deferno.core.data.activity

/**
 * The server's action verb (#364) — the typed twin of the wire's `action_kind` string.
 *
 * **Open by design.** The backend contract is "string on the wire, typed in code, with an [Other] tail"
 * so a six-month-old build never chokes on a verb a newer server emits. That tail is not defensive
 * paranoia: the ledger is forensic, and a client that dropped an entry it could not name would be
 * silently under-reporting an audit stream. An unknown verb keeps its raw token, renders through the
 * generic "Updated an item" phrasing, and round-trips unchanged.
 *
 * Contrast [ActivityActorKind], which is a genuinely closed taxonomy and needs no such escape hatch.
 */
sealed interface ActivityActionKind {

    /** The exact wire token — round-trippable, including for [Other]. */
    val token: String

    data object Created : ActivityActionKind { override val token = "created" }
    data object Updated : ActivityActionKind { override val token = "updated" }
    data object Deleted : ActivityActionKind { override val token = "deleted" }
    data object StatusChanged : ActivityActionKind { override val token = "status_changed" }
    data object Moved : ActivityActionKind { override val token = "moved" }
    data object Split : ActivityActionKind { override val token = "split" }
    data object Merged : ActivityActionKind { override val token = "merged" }
    data object Converted : ActivityActionKind { override val token = "converted" }
    data object Rescheduled : ActivityActionKind { override val token = "rescheduled" }
    data object CommentAdded : ActivityActionKind { override val token = "comment_added" }
    data object CommentEdited : ActivityActionKind { override val token = "comment_edited" }
    data object CommentDeleted : ActivityActionKind { override val token = "comment_deleted" }
    data object AttachmentAdded : ActivityActionKind { override val token = "attachment_added" }
    data object AttachmentDeleted : ActivityActionKind { override val token = "attachment_deleted" }
    data object AttachmentCaptioned : ActivityActionKind { override val token = "attachment_captioned" }
    data object PlanAdded : ActivityActionKind { override val token = "plan_added" }
    data object PlanRemoved : ActivityActionKind { override val token = "plan_removed" }
    data object PlanReordered : ActivityActionKind { override val token = "plan_reordered" }

    /** A verb this build doesn't know — carries the raw wire string so nothing is lost. */
    data class Other(override val token: String) : ActivityActionKind

    companion object {
        private val known = listOf(
            Created, Updated, Deleted, StatusChanged, Moved, Split, Merged, Converted, Rescheduled,
            CommentAdded, CommentEdited, CommentDeleted,
            AttachmentAdded, AttachmentDeleted, AttachmentCaptioned,
            PlanAdded, PlanRemoved, PlanReordered,
        )

        /** Decode a wire/stored token. Never fails — an unrecognised token becomes [Other]. */
        fun fromToken(token: String): ActivityActionKind =
            known.firstOrNull { it.token == token } ?: Other(token)
    }
}

/**
 * Who performed the action (#364). A **closed** taxonomy, unlike [ActivityActionKind]: it is broad enough
 * that a new caller maps onto an existing category (a new integration is still a [Webhook] with a
 * `provider`), so it needs no open tail. An unrecognised stored token still degrades to [Unknown] rather
 * than throwing — a diagnostics surface must never crash the screen.
 *
 * Attribution is **server-validated on ingest**, which makes it the trustworthy half of an entry; the
 * timestamp and source are client-asserted and merely advisory.
 */
enum class ActivityActorKind {
    Human,
    Assistant,
    System,
    Webhook,
    Unknown,
    ;

    val token: String get() = name.lowercase()

    companion object {
        fun fromToken(token: String): ActivityActorKind =
            entries.firstOrNull { it.token == token.lowercase() } ?: Unknown
    }
}

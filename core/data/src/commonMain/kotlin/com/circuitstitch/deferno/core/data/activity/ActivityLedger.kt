package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.CommentTargets
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Who made a change (issue #260). A local app-side write is [Mobile]; once a server-sourced reconcile
 * records remote changes, those carry [Website] / [Mcp] (the "via Website" / "via MCP agent" labels in
 * the design). Decoded defensively — an unknown stored token degrades to [Unknown] rather than throwing.
 */
enum class ActivitySource {
    Mobile,
    Website,
    Mcp,
    Unknown,
    ;

    companion object {
        fun fromToken(token: String): ActivitySource = entries.firstOrNull { it.name == token } ?: Unknown
    }
}

/**
 * One recorded change in the offline-first activity ledger (#260). It mirrors the outbox's rendered
 * shape — the structured [target] (e.g. "task:{id}", "create:Task:{id}", "plan:{date}:{tz}"), the wire
 * [method], and the request [path] — so the human [summary] and the deep-link [itemId] are derived at
 * read-time and can be refined without a schema migration. [source] is who made it; [recordedAt] is when
 * the change was applied (not when it later synced).
 *
 * [body] / [before] carry the change payload for a true old->new field diff (the Activity detail sheet +
 * the Task Trail): [body] is the rendered new-value PATCH/POST JSON the outbox already computed (kept for
 * every write); [before] is the matching old-value JSON, snapshotted just before the optimistic apply
 * (Task field edits only). Both null on pre-diff rows / writers that don't snapshot — such a row renders
 * the coarse [summaryInfo] with no diff. The typed diff is derived at read-time by [changes].
 */
data class ActivityEntry(
    val seq: Long,
    val recordedAt: Instant,
    val source: ActivitySource,
    val target: String,
    val method: OutboxMethod,
    val path: List<String>,
    val body: String? = null,
    val before: String? = null,
)

/** The coarse verb of a recorded change — the typed twin of [summary] for locale-aware rendering. */
enum class ActivityVerb {
    ChangedSettings,
    Created,
    MovedItem,
    UpdatedPlan,
    DeletedTask,
    UpdatedTask,
    ClearedOccurrence,
    UpdatedOccurrence,
    UpdatedItem,
    Commented,
}

/**
 * The typed feed summary: the [verb], plus the lowercase item-kind token it acted on ("task",
 * "chore", "habit", "event") when the verb is kind-qualified ([ActivityVerb.Created] /
 * the occurrence verbs) — null where the verb says it all. The View maps this to a localized
 * one-liner; [summary] stays as the English rendering for the SwiftUI bridges.
 */
data class ActivitySummary(val verb: ActivityVerb, val kindToken: String? = null)

/**
 * The typed feed summary, derived from the structured [ActivityEntry.target] + method. Kept
 * deliberately coarse for v1 ("Updated a task", not "Renamed a task") — the row links to the thing
 * it changed, so the verb need only orient. Refine here freely; nothing is persisted.
 */
fun ActivityEntry.summaryInfo(): ActivitySummary {
    if (target == "settings") return ActivitySummary(ActivityVerb.ChangedSettings)
    val parts = target.split(":")
    return when (parts.firstOrNull()) {
        "create" -> ActivitySummary(ActivityVerb.Created, parts.getOrElse(1) { "item" }.lowercase())
        "item" -> ActivitySummary(ActivityVerb.MovedItem)
        "plan" -> ActivitySummary(ActivityVerb.UpdatedPlan)
        "task" -> ActivitySummary(if (method == OutboxMethod.Delete) ActivityVerb.DeletedTask else ActivityVerb.UpdatedTask)
        "occurrence" -> ActivitySummary(
            if (method == OutboxMethod.Delete) ActivityVerb.ClearedOccurrence else ActivityVerb.UpdatedOccurrence,
            parts.getOrElse(1) { "event" }.lowercase(),
        )
        // ponytail: one coarse "Commented on an item" verb covers post/edit/delete — the ledger is
        // deliberately coarse (its own KDoc) and comment rows don't deep-link, so a delete-specific verb
        // earns nothing yet. Split it off if the Activity feed ever needs to distinguish them.
        "comment", "comment-create" -> ActivitySummary(ActivityVerb.Commented)
        else -> ActivitySummary(ActivityVerb.UpdatedItem)
    }
}

/** The item id this change touched for deep-linking, or null where there is no single item (plan/settings). */
fun ActivityEntry.itemId(): String? {
    val parts = target.split(":")
    return when (parts.firstOrNull()) {
        "task", "item" -> parts.getOrNull(1)
        "create" -> parts.getOrNull(2)
        else -> null // plan / settings / occurrence (keyed by series, not a single item) have no deep link yet
    }
}

/**
 * The task a comment row (`comment-create:` / new-shape `comment:<taskId>:<id>`) touched, or null (legacy
 * id-only comment target, or a non-comment row). Kept separate from [itemId] so a comment row can resolve
 * its item ref + deep-link in the Activity feed **without** pulling comment entries into the Task Trail's
 * `itemId()`-keyed ledger filter.
 */
fun ActivityEntry.commentTaskId(): String? = CommentTargets.taskId(target)

/**
 * The local source-of-truth port for the activity ledger (#260) — the read-only twin of
 * [com.circuitstitch.deferno.core.data.outbox.OutboxStore]. The write path records through [record] (via
 * the [com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore] decorator, so every write
 * is captured at one choke-point); the Activity screen observes [recent] reverse-chronologically.
 */
interface ActivityLedgerStore {

    /**
     * Append one applied change: its [source], the outbox [target] + [request] (its `body` is the
     * new-value JSON), the pre-apply old-value JSON [before] (null when not snapshotted), at [now]
     * (apply time).
     */
    suspend fun record(source: ActivitySource, target: String, request: OutboxRequest, before: String?, now: Instant)

    /** The most-recent [limit] entries, newest first — observed so the feed re-emits as changes land. */
    fun recent(limit: Long = 200): Flow<List<ActivityEntry>>

    /** Clears the ledger (account sign-out cleanup). */
    suspend fun clear()
}

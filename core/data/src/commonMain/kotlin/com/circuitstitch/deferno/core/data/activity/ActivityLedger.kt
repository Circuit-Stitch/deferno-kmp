package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.outbox.CommentTargets
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.data.outbox.OutboxRequest
import kotlinx.coroutines.flow.Flow
import kotlin.time.Instant

/**
 * Who made a change (#260, extended for the server ledger in #364). A local app-side write is [Mobile];
 * the `?since=` reconcile brings back rows from every other surface — [Website] / [Mcp] (the "via Website"
 * / "via MCP agent" labels in the design), plus [Api] (a raw bearer-token caller) and [System] (a
 * server-driven action such as a cascade auto-drop, which has no human actor at all).
 *
 * Decoded defensively — an unknown stored or wire token degrades to [Unknown] rather than throwing.
 * Note the wire spells these lowercase and calls the web surface `web`, so decode through [fromWire],
 * not [fromToken]: the latter reads the *stored* form, which is this enum's own name.
 */
enum class ActivitySource {
    Mobile,
    Website,
    Mcp,
    Api,
    System,
    Unknown,
    ;

    companion object {
        /** Decode the STORED form (this enum's own `name`). */
        fun fromToken(token: String): ActivitySource = entries.firstOrNull { it.name == token } ?: Unknown

        /**
         * Decode the WIRE form (`mobile` | `web` | `mcp` | `api` | `system` | `unknown`). `web` is the
         * one token that isn't just a lowercase [name] — the product term is "Website".
         */
        fun fromWire(token: String?): ActivitySource = when (token?.lowercase()) {
            "mobile" -> Mobile
            "web" -> Website
            "mcp" -> Mcp
            "api" -> Api
            "system" -> System
            else -> Unknown
        }
    }
}

/**
 * One change in the activity ledger — either an **optimistic** row this device recorded at write time, or
 * an **authoritative** row the `?since=` reconcile pulled back from the server (#364). The two are the
 * same type on purpose: they are unioned by [entryId] into one feed, and a locally-recorded row is
 * *replaced in place* by its server twin rather than double-counted.
 *
 * ## Which half of the row is populated
 *
 * The outbox-derived half ([target], [method], [path], [body], [before]) is what a local write captures:
 * the structured target (e.g. `"task:{id}"`, `"create:Task:{id}"`, `"plan:{date}:{tz}"`), the wire verb,
 * and the rendered request JSON. The server half ([actionKind], [actorKind], [serverItemId], [occurrence],
 * [changedFields], [detail], [observedAt]) arrives with the reconcile.
 *
 * A row can carry **either or both**: a purely local row has no [actionKind] yet (its verb is derived from
 * [target] + [method]); a purely server-sourced row has an empty [target] and no [body]. The read-time
 * derivations below prefer the server vocabulary and fall back to the outbox one, so both render.
 *
 * ## Times
 *
 * [recordedAt] is when this device applied the change. [occurredAt] is the actor's wall-clock — the axis
 * the feed sorts and displays; it equals [recordedAt] for a local write and comes from the server for a
 * remote one. It is always present: migration 16 back-filled it from [recordedAt] on every upgraded row,
 * so the sort axis and the displayed instant are one value rather than two derivations that can disagree.
 * [observedAt] is the server clock, the axis `?since=` pages; it is the marker that a row has an
 * authoritative twin.
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
    val entryId: String? = null,
    // Defaults to the apply time — exactly what `recordLocal` writes for an unstamped route and what
    // migration 16 back-filled — so a caller holding only a local write need not restate it.
    val occurredAt: Instant = recordedAt,
    val observedAt: Instant? = null,
    val actionKind: ActivityActionKind? = null,
    val actorKind: ActivityActorKind? = null,
    val provider: String? = null,
    val serverItemId: String? = null,
    val occurrence: String? = null,
    val seriesId: String? = null,
    val changedFields: List<String> = emptyList(),
    val detail: String? = null,
) {
    /**
     * Whether the server has confirmed this change. `false` means "optimistic, not yet reconciled" — the
     * ADR's *superseded* distinction: an un-acknowledged row is not a failed one, merely one whose
     * authoritative twin hasn't arrived.
     */
    val isAcknowledged: Boolean get() = observedAt != null
}

/**
 * The coarse verb of a recorded change — the typed twin the View maps to a localized string.
 *
 * The first ten entries predate the server ledger and are derived from an outbox [ActivityEntry.target];
 * the rest are the server's own vocabulary (#364). Both sets render through the same `when`, and because
 * that `when` is exhaustive on Android/desktop, adding a verb here is a compile error until every Compose
 * surface names it — which is the intended forcing function.
 */
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

    // The server vocabulary (#364).
    StatusChanged,
    DeletedItem,
    Split,
    Merged,
    Converted,
    Rescheduled,
    CommentEdited,
    CommentDeleted,
    AttachmentAdded,
    AttachmentDeleted,
    AttachmentCaptioned,
    PlanAdded,
    PlanRemoved,
    PlanReordered,
}

/**
 * The typed feed summary: the [verb], plus the lowercase item-kind token it acted on ("task", "chore",
 * "habit", "event") when the verb is kind-qualified ([ActivityVerb.Created] / the occurrence verbs) —
 * null where the verb says it all. The View maps this to a localized one-liner.
 */
data class ActivitySummary(val verb: ActivityVerb, val kindToken: String? = null)

/**
 * The typed feed summary. Prefers the **server's** `action_kind` when the row carries one, and falls back
 * to deriving a verb from the structured [ActivityEntry.target] + method for a purely local row.
 *
 * The fallback stays deliberately coarse ("Updated a task", not "Renamed a task") — the row links to the
 * thing it changed, so the verb need only orient. The server path can afford to be precise because the
 * server names the verb outright. Nothing here is persisted, so both may be refined freely.
 */
fun ActivityEntry.summaryInfo(): ActivitySummary =
    actionKind?.let { serverSummary(it) } ?: targetSummary()

/** The precise summary for a row the server has named. [ActivityActionKind.Other] falls through to the generic verb. */
private fun ActivityEntry.serverSummary(kind: ActivityActionKind): ActivitySummary {
    val itemKind = detailItemKind()
    return when (kind) {
        ActivityActionKind.Created -> ActivitySummary(ActivityVerb.Created, itemKind ?: "item")
        ActivityActionKind.Updated ->
            // Reuse the kind-specific phrasing where the wire names a Task, else the generic one; the
            // remaining kinds have no dedicated "updated a <kind>" string and read fine as "an item".
            if (itemKind == "task") ActivitySummary(ActivityVerb.UpdatedTask) else ActivitySummary(ActivityVerb.UpdatedItem)
        ActivityActionKind.Deleted ->
            if (itemKind == "task") ActivitySummary(ActivityVerb.DeletedTask) else ActivitySummary(ActivityVerb.DeletedItem)
        // An occurrence-scoped status change is a firing being marked/cleared, not an item edit — the
        // pre-existing occurrence verbs already say that precisely, so reuse them rather than minting
        // a second vocabulary for the same event.
        ActivityActionKind.StatusChanged -> when {
            occurrence == null -> ActivitySummary(ActivityVerb.StatusChanged)
            detailCleared() -> ActivitySummary(ActivityVerb.ClearedOccurrence, itemKind ?: "event")
            else -> ActivitySummary(ActivityVerb.UpdatedOccurrence, itemKind ?: "event")
        }
        ActivityActionKind.Moved -> ActivitySummary(ActivityVerb.MovedItem)
        ActivityActionKind.Split -> ActivitySummary(ActivityVerb.Split)
        ActivityActionKind.Merged -> ActivitySummary(ActivityVerb.Merged)
        ActivityActionKind.Converted -> ActivitySummary(ActivityVerb.Converted)
        ActivityActionKind.Rescheduled -> ActivitySummary(ActivityVerb.Rescheduled)
        ActivityActionKind.CommentAdded -> ActivitySummary(ActivityVerb.Commented)
        ActivityActionKind.CommentEdited -> ActivitySummary(ActivityVerb.CommentEdited)
        ActivityActionKind.CommentDeleted -> ActivitySummary(ActivityVerb.CommentDeleted)
        ActivityActionKind.AttachmentAdded -> ActivitySummary(ActivityVerb.AttachmentAdded)
        ActivityActionKind.AttachmentDeleted -> ActivitySummary(ActivityVerb.AttachmentDeleted)
        ActivityActionKind.AttachmentCaptioned -> ActivitySummary(ActivityVerb.AttachmentCaptioned)
        ActivityActionKind.PlanAdded -> ActivitySummary(ActivityVerb.PlanAdded)
        ActivityActionKind.PlanRemoved -> ActivitySummary(ActivityVerb.PlanRemoved)
        ActivityActionKind.PlanReordered -> ActivitySummary(ActivityVerb.PlanReordered)
        is ActivityActionKind.Other -> ActivitySummary(ActivityVerb.UpdatedItem)
    }
}

/** The pre-#364 derivation, from the outbox target + method. Still the only signal on an un-reconciled row. */
private fun ActivityEntry.targetSummary(): ActivitySummary {
    if (target == "settings") return ActivitySummary(ActivityVerb.ChangedSettings)
    val parts = target.split(":")
    return when (parts.firstOrNull()) {
        "create" -> ActivitySummary(ActivityVerb.Created, parts.getOrElse(1) { "item" }.lowercase())
        "item" -> ActivitySummary(ActivityVerb.MovedItem)
        "plan" -> ActivitySummary(ActivityVerb.UpdatedPlan)
        "task" -> ActivitySummary(if (method == OutboxMethod.Delete) ActivityVerb.DeletedTask else ActivityVerb.UpdatedTask)
        "occurrence" -> ActivitySummary(
            // The clear verb is now a POST soft-delete (`…/occurrences/{date}/clear`, #364), so the
            // method alone no longer distinguishes it — the path's trailing segment does.
            if (path.lastOrNull() == "clear") ActivityVerb.ClearedOccurrence else ActivityVerb.UpdatedOccurrence,
            parts.getOrElse(1) { "event" }.lowercase(),
        )
        // ponytail: one coarse "Commented on an item" verb covers post/edit/delete for a LOCAL row — the
        // target can't tell them apart. A reconciled row gets the precise verb from `action_kind`.
        "comment", "comment-create" -> ActivitySummary(ActivityVerb.Commented)
        else -> ActivitySummary(ActivityVerb.UpdatedItem)
    }
}

/**
 * The item id this change touched for deep-linking, or null where there is no single item. Prefers the
 * server's [ActivityEntry.serverItemId]; a plan-scoped server row is filtered out because the backend
 * files those under an org sentinel rather than a real item, which would deep-link nowhere.
 */
fun ActivityEntry.itemId(): String? {
    serverItemId?.let { id ->
        val planScoped = actionKind == ActivityActionKind.PlanReordered
        return if (planScoped) null else id
    }
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
 * The local source-of-truth port for the activity ledger — an **optimistic cache** of the server's
 * Activity ledger since #364, not a source of truth. The write path records through [recordLocal] (via the
 * [com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore] decorator, so every outbox write
 * is captured at one choke-point); the reconcile merges authoritative rows through [upsertRemote]; the
 * Activity screen observes [recent] reverse-chronologically.
 */
interface ActivityLedgerStore {

    /**
     * Append one locally-applied change: its [source], the outbox [target] + [request] (its `body` is the
     * new-value JSON), the pre-apply old-value JSON [before] (null when not snapshotted), at [now] (apply
     * time). [stamp] is the client-minted merge key that also rode on the mutation body — null for a route
     * that cannot carry activity metadata, in which case the row can never be deduped against its server
     * twin and is simply superseded by it. [actionKind] names the verb outright for a write path that
     * knows it (the online-only attachment/convert seams, whose outbox target says nothing useful).
     */
    suspend fun recordLocal(
        source: ActivitySource,
        target: String,
        request: OutboxRequest,
        before: String?,
        now: Instant,
        stamp: ActivityStamp? = null,
        actionKind: ActivityActionKind? = null,
    )

    /**
     * Merge a page of authoritative server entries, keyed by `entry_id` — grow-only, server wins. An entry
     * whose id matches an optimistic row overwrites that row's server half in place, which is what stops a
     * local write appearing twice once the reconcile catches up.
     */
    suspend fun upsertRemote(entries: List<RemoteActivityEntry>)

    /** The most-recent [limit] entries, newest first by [ActivityEntry.occurredAt] — observed so the feed re-emits as changes land. */
    fun recent(limit: Long = 200): Flow<List<ActivityEntry>>

    /** Drop rows whose [ActivityEntry.occurredAt] is older than [cutoff], keeping the local window a subset of the server's. */
    suspend fun pruneOlderThan(cutoff: Instant)

    /** The stored `?since=` watermark, or null before the first successful sync. */
    suspend fun syncCursor(): String?

    /** Advance the `?since=` watermark after a successful page. */
    suspend fun setSyncCursor(cursor: String?, now: Instant)

    /** Clears the ledger and its cursor (account sign-out cleanup). */
    suspend fun clear()
}

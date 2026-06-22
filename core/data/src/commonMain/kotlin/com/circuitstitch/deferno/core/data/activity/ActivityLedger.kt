package com.circuitstitch.deferno.core.data.activity

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
 */
data class ActivityEntry(
    val seq: Long,
    val recordedAt: Instant,
    val source: ActivitySource,
    val target: String,
    val method: OutboxMethod,
    val path: List<String>,
)

/**
 * A human one-liner for the feed, derived from the structured [ActivityEntry.target] + method. Kept
 * deliberately coarse for v1 ("Updated a task", not "Renamed a task") — the row links to the thing it
 * changed, so the verb need only orient. Refine here freely; nothing is persisted.
 */
fun ActivityEntry.summary(): String {
    if (target == "settings") return "Changed settings"
    val parts = target.split(":")
    return when (parts.firstOrNull()) {
        "create" -> "Created a ${parts.getOrElse(1) { "item" }.lowercase()}"
        "item" -> "Moved an item"
        "plan" -> "Updated your plan"
        "task" -> if (method == OutboxMethod.Delete) "Deleted a task" else "Updated a task"
        "occurrence" -> {
            val kind = parts.getOrElse(1) { "event" }.lowercase()
            if (method == OutboxMethod.Delete) "Cleared a $kind occurrence" else "Updated a $kind occurrence"
        }
        else -> "Updated an item"
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
 * The local source-of-truth port for the activity ledger (#260) — the read-only twin of
 * [com.circuitstitch.deferno.core.data.outbox.OutboxStore]. The write path records through [record] (via
 * the [com.circuitstitch.deferno.core.data.outbox.LedgerRecordingOutboxStore] decorator, so every write
 * is captured at one choke-point); the Activity screen observes [recent] reverse-chronologically.
 */
interface ActivityLedgerStore {

    /** Append one applied change: its [source], the outbox [target] + [request], at [now] (apply time). */
    suspend fun record(source: ActivitySource, target: String, request: OutboxRequest, now: Instant)

    /** The most-recent [limit] entries, newest first — observed so the feed re-emits as changes land. */
    fun recent(limit: Long = 200): Flow<List<ActivityEntry>>

    /** Clears the ledger (account sign-out cleanup). */
    suspend fun clear()
}

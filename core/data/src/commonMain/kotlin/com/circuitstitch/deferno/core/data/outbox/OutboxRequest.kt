package com.circuitstitch.deferno.core.data.outbox

/**
 * The HTTP verb an [OutboxRequest] dispatches with (ADR-0001, #23). Deliberately a closed set of the
 * verbs the v1 intent table uses — `PATCH` (Task field edits), `POST` (pin / plan ops / occurrence
 * mark + reschedule), `PUT` (chore occurrence set-status, #74), and `DELETE` (soft-delete a Task /
 * clear an occurrence) — rather than the open Ktor `HttpMethod`, so an outbox row can only ever decode
 * to a verb the sender knows how to issue. The wire-token decode degrades an unknown stored value
 * defensively (see [SqlDelightOutboxStore]).
 */
enum class OutboxMethod { Patch, Post, Put, Delete }

/**
 * How one queued occurrence write participates in the flush-time collapse (#396). It is **declared** by
 * the intent that built the request ([OutboxRequest.collapseRole]) and persisted on the outbox row, so
 * the coalescer reads a fact the route's author stated rather than re-deriving one from the rendered
 * [OutboxRequest.path] at replay time.
 */
enum class CollapseRole {

    /**
     * An **absolute** per-firing set-state write, so a later one makes every earlier one on the same
     * firing redundant. Declared by the three [MarkOccurrence] shapes and by [ClearOccurrence] — one role
     * over route tails with nothing in common, which is the whole reason it is stated rather than matched
     * (they are listed to show that spread, not as a pattern anything keys on):
     *
     * - `POST habits/{id}/occurrences` — the habit binary mark (its date rides in the body, not the path)
     * - `PUT chores/{id}/occurrences/{date}` — the chore set-status mark
     * - `POST events/{id}/occurrences/{date}` — the event action mark
     * - `POST {kind}/{id}/occurrences/{date}/clear` — the forgiving undo (#364)
     *
     * Collapsing a mark into a later clear is safe server-side: clear is `set_…_occurrence(id, date,
     * None)`, which returns `204` whether or not a status was ever written, so clearing a firing whose
     * mark never went out is not an error.
     */
    Absolute,

    /**
     * A **barrier**: [RescheduleOccurrence] (`POST {kind}/{id}/occurrences/{date}/reschedule`), plus every
     * write that declared no role at all. A barrier is never dropped, never absorbs a predecessor, and ends
     * the collapsible run for its key — the entries before it and the entries after it compact independently.
     *
     * A reschedule is a barrier on purpose, and it is the one cell of the table it is tempting to get
     * wrong. The server force-writes **two** rows (the origin date to dropped with a `rescheduled_to`
     * pointer, the destination date to scheduled), so a preceding mark on the origin date is arguably
     * redundant. Collapsing it would nonetheless be wrong twice over: it would make the client queue's
     * semantics depend on a backend implementation detail that the contract does not promise, and it
     * would lose the server-side Activity entry for a mark the user really did perform.
     */
    Barrier,
}

/**
 * The already-computed wire request an outbox entry replays (ADR-0001, #23) — the unit the offline
 * write path persists and re-sends. A [com.circuitstitch.deferno.core.data.outbox.Mutation] is
 * transient: it exists only long enough to apply optimistically to the local cache and to produce
 * *this* request, which is what the outbox stores and replays.
 *
 * **Why store the request, not the intent.** Persisting the rendered [method]/[path]/[body] (rather
 * than a serialized polymorphic intent) means replay re-sends byte-identical bytes — which is what
 * makes replay perfectly idempotent (#23) — and sidesteps polymorphic serialization of the domain
 * types the intents carry.
 *
 * - [path] — the request's path segments (e.g. `["tasks", "<id>"]`), appended onto the client base URL.
 * - [body] — the **rendered minimal JSON object string** for a PATCH/POST, or `null` for a bodiless
 *   DELETE. It is built once at enqueue time from a `JsonObject` carrying only the keys the intent
 *   changes — explicit `null` for a "clear" field, the value for a set, and **never an absent field**
 *   (ADR-0011) — and sent verbatim, so a missing value can never clobber a server field.
 * - [acceptsActivityStamp] — whether this route accepts the `activity` ingest sibling (#364, ADR-0048).
 *   Declared **here, by whoever picked the route**, rather than re-derived downstream by matching [path]
 *   segments: a whitelist written against path shape drifts one segment loose from the contract sooner or
 *   later, and an unexpected key on a strict payload is a `422` — which [KtorOutboxRequestSender]
 *   classifies Terminal and [OutboxProcessor] then dead-letters, destroying the user's write rather than
 *   merely losing an audit row. Opt-in, so a route that forgets to declare it fails closed and costs only
 *   a server-minted entry id. Read once, at enqueue ([LedgerRecordingOutboxStore]); a row decoded back out
 *   of the database reports `false` and never needs it, because the stamp was merged into [body] before
 *   that row was ever written.
 * - [collapseRole] — how a queued occurrence write participates in the flush-time collapse (#396). Declared
 *   **here, by whoever picked the route**, on exactly the principle stated above: the role belongs to the
 *   intent that chose the endpoint, and a whitelist re-deriving it from [path] segments drifts one segment
 *   loose from the contract sooner or later. **Unlike [acceptsActivityStamp] it is persisted** (a
 *   `collapse_role` column): [coalesceOccurrences] runs at flush, over rows decoded back out of the
 *   database long after the intent that declared them is gone, so the declaration has to survive the round
 *   trip to be readable at all. Defaults to [CollapseRole.Barrier] so an undeclared write **fails closed** —
 *   never collapsed into, never absorbing a predecessor — costing at worst an uncompacted replay rather
 *   than the user's write.
 */
data class OutboxRequest(
    val method: OutboxMethod,
    val path: List<String>,
    val body: String? = null,
    val acceptsActivityStamp: Boolean = false,
    val collapseRole: CollapseRole = CollapseRole.Barrier,
)

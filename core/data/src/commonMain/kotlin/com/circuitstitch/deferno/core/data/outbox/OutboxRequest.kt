package com.circuitstitch.deferno.core.data.outbox

/**
 * The HTTP verb an [OutboxRequest] dispatches with (ADR-0001, #23). Deliberately a closed set of the
 * three verbs the v1 intent table uses — `PATCH` (Task field edits), `POST` (pin / plan ops), and
 * `DELETE` (soft-delete a Task) — rather than the open Ktor `HttpMethod`, so an outbox row can only
 * ever decode to a verb the sender knows how to issue. The wire-token decode degrades an unknown
 * stored value defensively (see [SqlDelightOutboxStore]).
 */
enum class OutboxMethod { Patch, Post, Delete }

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
 */
data class OutboxRequest(
    val method: OutboxMethod,
    val path: List<String>,
    val body: String? = null,
)

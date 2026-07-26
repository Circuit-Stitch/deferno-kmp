package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * One page of the server's Activity ledger (`GET /activity` → `Envelope<ActivityFeed>`, #364).
 *
 * The endpoint pages **two mutually exclusive keyset axes**, and each mode nulls the other's cursor:
 * - **feed** (`?before=`, or neither) — newest-first over `occurred_at`; emits [nextBefore] only.
 * - **sync** (`?since=`) — oldest-first over `observed_at`, gapless; emits [nextSince] only.
 *
 * Supplying both cursors is a `400`. Note **end-of-feed is an empty [entries] list, not a null cursor**:
 * the last non-empty page still hands back a token, so a pager that stops on `cursor == null` would spin
 * forever on a quiet ledger.
 *
 * The envelope *also* declares a sibling top-level `next_since` (it is on every `Envelope_*` schema), but
 * `GET /activity` never populates it — the cursor to read is this one, inside `data`. That is what lets
 * the feed ride the existing `requestApi` unchanged, since `requestApi` returns only `envelope.data`.
 */
@Serializable
data class ActivityFeedDto(
    val entries: List<ActivityEntryDto> = emptyList(),
    @SerialName("next_before") val nextBefore: String? = null,
    @SerialName("next_since") val nextSince: String? = null,
)

/**
 * One rendered ledger entry. The server stores entries **split-envelope** — plaintext structural fields
 * (queryable without the org DEK) plus an org-DEK-encrypted `detail` blob — and this DTO is the decrypted
 * read projection of both halves.
 *
 * ## Why the enums are `String`, not typed
 *
 * [actionKind], [actorKind] and [source] are bare strings on the wire *by contract*, not by omission: the
 * backend ADR chose "string-on-wire + typed enum with an `Other` tail" precisely so a six-month-old build
 * never fails to deserialize a verb a newer server emits. Typing them here as `@Serializable enum` would
 * reintroduce the failure mode the contract exists to prevent — `coerceInputValues` would quietly coerce a
 * genuinely new verb to `Unknown` and lose the token. They are condensed to typed values (keeping the raw
 * token on the `Other` branch) in `core:data`'s `ActivityActionKind`/`ActivityActorKind`.
 *
 * ## The two timestamps are not interchangeable
 *
 * [occurredAt] is the actor's wall-clock — what the feed displays and sorts by, and what an offline client
 * asserts. [observedAt] is the server clock: the forensic anchor, and the axis `?since=` pages. A client
 * that sorts by `observed_at` would make a phone's morning work "pop up" when its outbox flushed hours later.
 *
 * ## `detail` is deliberately untyped
 *
 * Its shape varies per [actionKind] (`{"title","item_kind"}` for a create, `{"item_kind","fields":{k:{old,new}}}`
 * for an update, `{"from_date","to_date"}` for a reschedule, …), so it is kept as the raw [JsonElement] and
 * decoded on read — the same posture [TaskActionDto.kind] takes. It can also be JSON `null`: the server
 * degrades an entry whose org DEK it cannot unwrap to a null detail rather than dropping the row, so a
 * reader must render the structural fields alone in that case.
 *
 * [changedFields] rides *outside* the encrypted blob — it is the plaintext NAMES of the fields an `updated`
 * action touched (the old→new VALUES stay sealed in [detail]). Non-empty only for `updated`.
 */
@Serializable
data class ActivityEntryDto(
    @SerialName("entry_id") val entryId: String,
    @SerialName("org_id") val orgId: String? = null,
    @SerialName("user_id") val userId: String? = null,
    @SerialName("actor_kind") val actorKind: String? = null,
    val provider: String? = null,
    val source: String? = null,
    @SerialName("item_id") val itemId: String,
    val occurrence: String? = null,
    @SerialName("series_id") val seriesId: String? = null,
    @SerialName("action_kind") val actionKind: String,
    @SerialName("occurred_at") val occurredAt: String,
    @SerialName("observed_at") val observedAt: String,
    @SerialName("changed_fields") val changedFields: List<String> = emptyList(),
    val detail: JsonElement? = null,
)

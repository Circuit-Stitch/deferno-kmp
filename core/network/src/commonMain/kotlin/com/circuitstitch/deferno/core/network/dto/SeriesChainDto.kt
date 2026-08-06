package com.circuitstitch.deferno.core.network.dto

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The wire `series_chain` block — the underlying records behind one recurring item, root → Head
 * (`contracts/openapi-0.1.json` → `components.schemas.SeriesChainView`, ADR-0053 decision 3). A
 * recurrence rule cannot be rewritten retroactively, so every rule change closes the current
 * [[Segment]] era and opens a new one linked to it; this block is how a client tells those records
 * apart while the item still reads as one continuous thing.
 *
 * **Detail read only, and that is a decided limit, not an oversight.** `GET /items` collapses chains
 * server-side with `SegmentRetention::DropSuperseded`, so the cold snapshot carries no era but the
 * current one — which is why this block is declared on [ItemView] (the spec's `ItemDetail` is
 * `allOf[ItemEnvelope_ItemView, {…, series_chain, …}]`, so the detail body is an item row with these
 * fields appended) yet never reaches a table. ADR-0053 accepts the consequence in as many words: *"a
 * chain era is reachable only once its item has been opened, since the per-segment inputs ride the
 * detail read rather than the snapshot"*. Caching an era no cold boot could refresh would turn that
 * honest limit into a stale lie.
 *
 * **Present only when the rule has changed at least once.** The spec documents `segments.len() >= 2`
 * whenever the field is present at all, so a one-era item carries no chain rather than a chain of one.
 *
 * **Every field defaulted even though the spec marks all four required (ADR-0005).** Same posture, same
 * reason, as [SeriesInputsDto]: tolerance belongs at the decode boundary. A strict field here would let
 * a malformed chain fail the **whole** `/items/{id}` decode — the detail would not open at all, which
 * is the very bug (#383) this block is being decoded to fix. A chain that cannot be read must degrade
 * to "no history", never to "no item". The all-or-nothing judgement lives one layer up, in
 * `mapper/SeriesChainMapper.kt`.
 */
@Serializable
data class SeriesChainDto(
    /**
     * The presented Head — the last live record of the chain, and the id every write and every other
     * read surface addresses this item by. Always equal to the response's top-level `id`; when the
     * chain's tip was archived this is the surviving predecessor, never the archived tip (which would
     * 404 if addressed). Nullable only for tolerance — the server always sends it.
     */
    val head: String? = null,
    /**
     * The record id the caller actually asked for. May name an **earlier** era than [head] when the
     * caller held an older id, which is exactly what makes a stale deep link still resolve.
     */
    val requested: String? = null,
    /** Every underlying record, root → [head], one entry per rule era. */
    val segments: List<SegmentDto> = emptyList(),
    /**
     * A link was unreadable server-side — a cycle, a dangling pointer, or the depth cap — so [segments]
     * may be **partial**. The honest flag rather than a 500 that would take out the whole detail, so it
     * is not a failure: a consumer renders what it has and says the history is incomplete.
     */
    val truncated: Boolean = false,
)

/**
 * One rule era of a [SeriesChainDto] — the spec's `SegmentView`, widened by ADR-0053 decision 3 from a
 * bare identifier to the era's own rule, its own expansion inputs and its tombstone.
 *
 * Two rules the fields cannot state and a reader cannot guess, both quoted from the backend's schema.
 * #383 only *carries* them; #395 owns honouring them — they are recorded here because the wire shape is
 * where someone will look first:
 *
 * - **A deleted era's occurrences are EXCLUDED.** It stays listed so the chain of records does not
 *   break, but it contributes linkage only — expanding it would resurrect dates the user deliberately
 *   removed.
 * - **[recurrence] is NOT truncated by the era that superseded it.** The rule still reads open-ended;
 *   what actually stops it is [series]`.until_utc`, an **exclusive** upper bound. Expand the rule
 *   without applying that bound and the era runs straight through the change, producing dates the
 *   following era already owns.
 */
@Serializable
data class SegmentDto(
    /**
     * This era's underlying record id. Addressable while [deletedAt] is `null`; a deleted era's id is
     * listed for linkage only and is not fetchable. A read addressed to *any* era of a chain answers
     * for the whole item, not for that era alone.
     */
    val id: String? = null,
    /** The rule in force during this era. `null` for a one-off Event — the only shape with no rule. */
    val recurrence: RecurrenceDto? = null,
    /**
     * This era's stored expansion inputs — see [SeriesInputsDto]. `null` is the same **elision** it is
     * everywhere else (the stored series behind the era has gone missing, or the record never had one),
     * never an era with no exclusions: when [recurrence] is non-null the era still recurs and is history
     * this device cannot expand, not a non-repeating era.
     */
    val series: SeriesInputsDto? = null,
    /** When this era was deleted, or `null` while it is live. See the class KDoc. */
    @SerialName("deleted_at") val deletedAt: String? = null,
)

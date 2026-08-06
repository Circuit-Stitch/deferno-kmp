package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * The underlying records behind one recurring item, root → Head — its [[Segment]] eras (ADR-0053
 * decision 3, backend `SeriesChainView`).
 *
 * A recurrence rule cannot be changed retroactively, so every rule change closes the current era and
 * opens a new one linked to it. The item still reads as one continuous thing; this is how a client can
 * tell the records apart when it needs to.
 *
 * **Present only when the rule has been changed at least once** — the backend documents `segments.size
 * >= 2` whenever the field is present at all, so a one-era item carries no chain rather than a chain of
 * one.
 *
 * **This rides the detail read and is never persisted.** `GET /items` collapses chains server-side with
 * `SegmentRetention::DropSuperseded`, so the cold snapshot carries no era but the current one — a cached
 * era would be a value no cold boot could ever refresh. ADR-0053 accepts the consequence knowingly: *"a
 * chain era is reachable only once its item has been opened, since the per-segment inputs ride the
 * detail read rather than the snapshot"*. Caching it would turn that honest limit into a stale lie.
 *
 * **Scope (#383 vs #395):** this type and its decode are #383's half — the transport and the state.
 * Everything *rendered* from it is #395: which era you are looking at, when each split, what the earlier
 * rules were, which are tombstoned, and any per-era grid expansion.
 */
data class SeriesChain(
    /**
     * The presented Head — the last live record of the chain, and the id every write and every other
     * read surface addresses this item by. Always equal to the response's top-level `id`. When the
     * chain's tip was archived this is the surviving predecessor, never the archived tip.
     */
    val head: String,
    /**
     * The record id the caller actually asked for. May name an earlier era than [head] when the caller
     * held an older id — which is exactly what makes a stale deep link still resolve.
     */
    val requested: String,
    /** Every underlying record, root → [head], one entry per rule era. */
    val segments: List<SeriesSegment> = emptyList(),
    /**
     * A link was unreadable — a cycle, a dangling pointer, or the depth cap — so [segments] may be
     * **partial**. This is the honest flag rather than a 500 that would take out the whole detail, so a
     * consumer renders what it has and says the history is incomplete; it is not a failure.
     */
    val truncated: Boolean = false,
)

/**
 * One rule era of a [SeriesChain].
 *
 * Two rules the fields cannot state and a reader cannot guess, both spelled out by the backend's own
 * schema. **#383 only carries them; #395 owns honouring them** — but they are recorded here because the
 * type is where someone will look:
 *
 * - **A deleted era's occurrences are excluded.** It stays listed so the chain of records does not
 *   break, but it contributes linkage *only* — expanding it would resurrect dates the user deliberately
 *   removed.
 * - **[recurrence] is not truncated by the era that superseded it.** The rule still reads open-ended;
 *   what actually stops it is [series].`untilUtc`, which is an **exclusive** upper bound. Expand the
 *   rule without applying that bound and the era runs straight through the change, producing dates the
 *   following era already owns.
 */
data class SeriesSegment(
    /**
     * This era's underlying record id. Addressable while [deletedAt] is `null`; a deleted era's id is
     * listed for linkage only and is not fetchable. Note that a read addressed to *any* era of a chain
     * answers for the whole item, not for that era alone.
     */
    val id: String,
    /** The rule in force during this era. `null` for a one-off Event — the only shape with no rule. */
    val recurrence: Recurrence? = null,
    /**
     * This era's stored expansion inputs. `null` is the same **elision** it is everywhere else — see
     * [SeriesInputs] — and never an era with no exclusions.
     */
    val series: SeriesInputs? = null,
    /** When this era was deleted, or `null` while it is live. See the class KDoc. */
    val deletedAt: Instant? = null,
) {
    /** Whether this era contributes linkage only — its dates must never be expanded. See the class KDoc. */
    val isTombstoned: Boolean get() = deletedAt != null
}

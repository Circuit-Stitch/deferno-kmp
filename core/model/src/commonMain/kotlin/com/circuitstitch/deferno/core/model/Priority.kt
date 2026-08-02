package com.circuitstitch.deferno.core.model

import kotlin.time.Instant

/**
 * An [Item]'s non-dated **urgency bucket** (CONTEXT.md → "Priority") — deadline-independent, peer to
 * `pinned`, carried on all four kinds. Condensed from the wire `Priority` (`fire`/`normal`/`backlog`);
 * the exact wire tokens live only on the DTO `@SerialName`s in `core:network`, never here (ADR-0011).
 *
 * Three levels is the minimum that serves both server-side consumers (Deferno ADR 2026-06-29
 * "priority-model"): a step **below** the default, so lowering an item's priority sinks it while
 * keeping it **visible** — never hidden, which is the hazard a "blocked by" edge would introduce —
 * and an explicit top lane.
 *
 * **Declaration order is the rank.** [bucketRank] is the ordinal, mirroring the server's
 * `#[repr(u8)] Fire = 0 … Backlog = 2`, so the two sides agree on ordering by construction — never
 * reorder these constants.
 */
enum class Priority {
    /** Top bucket — sorts above everything else, regardless of date. */
    Fire,

    /** The default. Sorts by date within its bucket. */
    Normal,

    /** Bottom bucket — sinks below everything, but stays **visible**. */
    Backlog,
    ;

    /** Lexicographic bucket rank; lower = more urgent. The ordinal *is* the rank. */
    val bucketRank: Int get() = ordinal

    companion object {
        /**
         * The value a row carries when the server sends none. A legacy row (or one whose `priority`
         * the wire omits) is [Normal], matching the backend's `#[serde(default)]`.
         */
        val Default: Priority = Normal
    }
}

/**
 * The canonical ranked-view sort key (#375) — **sort ascending for most-urgent-first**:
 * `(bucket rank, soonest relevant date, hard deadline, created)`.
 *
 * This is a deliberate port of the server's `models::priority::priority_sort_key`, kept
 * case-for-case with its unit tests: every ranked surface — server *or* client — applies the **same**
 * key, so a locally-ranked list and an `$orderby=priority_rank` request can't disagree.
 *
 * The *soonest relevant date* is the soft [targetDate] if set, else the hard [completeBy]. That is the
 * whole point of the soft date: a near "I want this done by" surfaces an item whose real deadline is
 * still far off. Undated items sink **within their bucket** via a [Long.MAX_VALUE] sentinel rather
 * than floating to the top.
 *
 * Pure and total — no clock, no I/O — so it is equally usable from a `sortedBy`, a comparator, or a
 * test. It ranks; it never filters: a [Priority.Backlog] item still has a key.
 *
 * **It is not a licence to reorder a curated surface.** The Pinned list, the Plan's user-arranged
 * order, and the item tree's root order stay exactly as the person arranged them (the server ADR is
 * explicit); this key belongs to *ranked* views only.
 */
fun prioritySortKey(
    priority: Priority,
    targetDate: Instant?,
    completeBy: Instant?,
    // Nullable where the server's own signature is not: an item always has a creation time, but a
    // *projection* of one (a search hit) may not carry it. An absent value sorts last like an absent
    // date, and it only ever decides a tie in which everything else is already equal.
    dateCreated: Instant?,
): PrioritySortKey = PrioritySortKey(
    bucketRank = priority.bucketRank,
    soonestRelevant = (targetDate ?: completeBy).toSortMillis(),
    deadline = completeBy.toSortMillis(),
    created = dateCreated.toSortMillis(),
)

/**
 * The comparable tuple [prioritySortKey] produces. A named, [Comparable] type rather than a raw
 * `Triple`/`List<Long>` so a caller can only ever compare like with like, and so the field names stay
 * self-describing at the call site (`sortedBy { prioritySortKey(...) }`).
 */
data class PrioritySortKey(
    val bucketRank: Int,
    val soonestRelevant: Long,
    val deadline: Long,
    val created: Long,
) : Comparable<PrioritySortKey> {
    override fun compareTo(other: PrioritySortKey): Int =
        compareValuesBy(this, other, { it.bucketRank }, { it.soonestRelevant }, { it.deadline }, { it.created })
}

/** Absent dates sort **last** within a bucket — the sentinel the server spells `i64::MAX`. */
private fun Instant?.toSortMillis(): Long = this?.toEpochMilliseconds() ?: Long.MAX_VALUE

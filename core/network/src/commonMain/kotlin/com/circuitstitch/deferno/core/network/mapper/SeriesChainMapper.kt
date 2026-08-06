package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.model.SeriesSegment
import com.circuitstitch.deferno.core.network.dto.SegmentDto
import com.circuitstitch.deferno.core.network.dto.SeriesChainDto
import kotlin.time.Instant

/**
 * The wire `series_chain` block → domain [SeriesChain] (#383, ADR-0053 decision 3) — the [[Segment]]
 * eras behind one recurring item, root → Head. The sibling of `SeriesInputsMapper.kt`, one level out:
 * that file maps the base block, this one maps the per-era copies of it plus the linkage around them.
 *
 * **Public, unlike its sibling, and deliberately so.** `SeriesInputsDto.toDomain()` is `internal`, so a
 * chain cannot be mapped from `core:data` — the segments carry `SeriesInputsDto`s and mapping them has
 * to happen inside this module. The whole chain therefore crosses the boundary already in domain shape.
 *
 * **Two different postures, one per level, and the difference is the point:**
 *
 * - **Within an era, all-or-nothing.** A segment's inputs are refused whole by
 *   `SeriesInputsMapper.kt` rather than salvaged, because a partially-read grid is not a smaller grid,
 *   it is a **wrong** one. That is inherited here unchanged: an unreadable `series` leaves the era with
 *   `series = null`, which is precisely what the wire's own `null` means — *"this era's dates cannot be
 *   reproduced locally"* — so the two are indistinguishable to a consumer, correctly.
 * - **Across eras, never all-or-nothing.** One unreadable era must not cost the user the other five.
 *   An era this reader cannot use is dropped from [SeriesChain.segments] and [SeriesChain.truncated] is
 *   forced true, so the drop is **announced rather than silent** — which is exactly what that flag
 *   already means downstream ("segments may be partial; render what you have and say the history is
 *   incomplete"). It says nothing about *where* the link broke, and never claimed to.
 *
 * Tolerance itself lives one layer further down still, in the all-defaulted [SeriesChainDto]: by the
 * time control reaches this file the detail response has already parsed. Refusing a chain here costs
 * the era history, not the item — and #383 is the bug where the item would not open **at all**.
 */
fun SeriesChainDto?.toDomain(): SeriesChain? {
    val dto = this ?: return null
    // The Head is the one field nothing else can supply. It is the id every write and every other read
    // surface addresses this item by, and the first segment is the ROOT era — the opposite end of the
    // chain — so it cannot stand in. A headless block is refused whole; the item still opens without it.
    val head = dto.head?.takeIf { it.isNotBlank() } ?: return null
    val segments = dto.segments.map { it.toDomainOrNull() }
    return SeriesChain(
        head = head,
        // An absent `requested` reads as "the caller asked for the Head" — the overwhelmingly common
        // case, and the quiet one. #395 uses the pair to tell someone they followed an older link, so
        // this fallback can only ever suppress that note; refusing the block would cost the whole era
        // history instead. Under-reporting is the recoverable direction here as everywhere else.
        requested = dto.requested?.takeIf { it.isNotBlank() } ?: head,
        segments = segments.filterNotNull(),
        truncated = dto.truncated || segments.any { it == null },
    )
}

/**
 * One wire era → [SeriesSegment]; `null` when this reader cannot use it, which drops that era alone and
 * flips [SeriesChain.truncated] — see the file KDoc. Exactly two things make an era unusable, and both
 * are refusals to state something false rather than tidiness:
 *
 * - **No id.** The id *is* the era: without it there is nothing to address, nothing to filter an
 *   activity timeline by, and no way to tell two eras apart.
 * - **A `deleted_at` that is present but unparseable.** Mapping it to `null` would present a deleted era
 *   as **live**, and a live era gets expanded (#395) — resurrecting dates the user deliberately removed,
 *   the one direction that is not recoverable. Fabricating an [Instant] to mark it tombstoned instead
 *   would invent a fact, which ADR-0053 forbids just as squarely. So the era is dropped and said so.
 *
 * [recurrence] needs no arm of its own: `RecurrenceDto.toDomain()` is already total, degrading an
 * unknown cadence token to `Cadence.Unmodelled` rather than throwing (#382), and a wire `null` is the
 * genuine one-off Event — the only recurring-item shape with no rule.
 */
internal fun SegmentDto.toDomainOrNull(): SeriesSegment? {
    val recordId = id?.takeIf { it.isNotBlank() } ?: return null
    // Absent/null is the live era; present-but-garbled refuses it. Not the same question.
    val deleted = if (deletedAt == null) null else deletedAt.toInstantOrNull() ?: return null
    return SeriesSegment(
        id = recordId,
        recurrence = recurrence.toDomain(),
        series = series.toDomain(),
        deletedAt = deleted,
    )
}

/**
 * Parses an era's `deleted_at` timestamp, or `null` when absent/unparseable.
 *
 * `runCatching`-wrapped on purpose: the same-named helper in `RecurringItemMapper.kt:292` is a bare
 * `Instant::parse` and **throws** on a garbled timestamp, which is a known sharp edge there and would be
 * a strictly worse one here — this block rides the single-item read that #383 exists to make open at all.
 */
private fun String?.toInstantOrNull(): Instant? =
    this?.let { runCatching { Instant.parse(it) }.getOrNull() }

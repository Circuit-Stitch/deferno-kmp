package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.SeriesOverride
import com.circuitstitch.deferno.core.network.dto.SeriesInputsDto
import com.circuitstitch.deferno.core.network.dto.SeriesOverrideDto
import kotlinx.datetime.LocalDateTime
import kotlin.time.Instant

/**
 * The wire `series` block → domain [SeriesInputs] (#410, ADR-0053 decision 2) — the mapping that turns
 * the additive wire block into the inputs `expandOccurrenceGrid` already knows how to consume, and the
 * last hop of the seam this client had built from both ends and joined in the middle nowhere.
 *
 * **All-or-nothing, and that is the safe direction.** Every arm below degrades the *whole* block to
 * `null` rather than salvaging the readable parts, because a partially-read grid is not a smaller grid,
 * it is a **wrong** one: a dropped `until_utc` invents firings past the end of a [[Segment]], a dropped
 * `exdates` entry resurrects a deleted firing, and a dropped override puts a rescheduled firing back on
 * the day it moved off. Under-reporting is recoverable — the domain already has a word for it, and
 * [SeriesInputs] spells it out: `null` means "this device cannot reproduce that grid". Over-reporting
 * would show the user firings that do not exist.
 *
 * That strictness costs nothing at the decode boundary and must not be confused with it. The **DTO** is
 * relentlessly tolerant (every field nullable-or-defaulted, every timestamp a raw `String`) so a
 * malformed block can never fail the enclosing `/items` decode — the whole-snapshot stall of #381. By
 * the time control reaches this file the response has already parsed; refusing a block here yields one
 * row with no expansion inputs, not a dead sync.
 */
internal fun SeriesInputsDto?.toDomain(): SeriesInputs? {
    val dto = this ?: return null
    // The anchor and the zone are the irreducible pair: without both there is no grid to reproduce,
    // and neither can be guessed. `complete_by` is NOT a fallback anchor — it is a walked cursor.
    val anchor = dto.dtstartLocal.toLocalDateTimeOrNull() ?: return null
    val tzid = dto.tzid?.takeIf { it.isNotBlank() } ?: return null
    // Present-but-unparseable is refused; absent is the open-ended series and stays `null`.
    val until = if (dto.untilUtc == null) null else dto.untilUtc.toInstantOrNull() ?: return null
    val exdates = dto.exdates.map { it.toLocalDateTimeOrNull() ?: return null }
    val overrides = dto.overrides.map { it.toDomainOrNull() ?: return null }
    return SeriesInputs(
        anchorLocal = anchor,
        tzid = tzid,
        untilUtc = until,
        exdates = exdates,
        overrides = overrides,
    )
}

/**
 * One wire exception → [SeriesOverride]; `null` when it cannot be read, which refuses the whole block.
 *
 * **The rename is the point.** The wire calls the moved start `dtstart_local` — the same key the block
 * itself uses for the *anchor*, one nesting level up — and the two mean opposite things: the anchor
 * never moves, this is precisely the movement. [SeriesOverride.movedToLocal] exists so the confusion
 * cannot survive the mapper, so do not "simplify" the name back on the way through.
 */
private fun SeriesOverrideDto.toDomainOrNull(): SeriesOverride? {
    val slot = recurrenceId.toLocalDateTimeOrNull() ?: return null
    // A cancel-only exception carries no moved time; a present-but-garbled one is refused.
    val moved = if (dtstartLocal == null) null else dtstartLocal.toLocalDateTimeOrNull() ?: return null
    return SeriesOverride(recurrenceId = slot, isCancelled = isCancelled, movedToLocal = moved)
}

/** Parses a wire wall time (`2026-08-10T23:59:59`, no offset), or `null` when absent/unparseable. */
private fun String?.toLocalDateTimeOrNull(): LocalDateTime? =
    this?.let { runCatching { LocalDateTime.parse(it) }.getOrNull() }

/** Parses the `until_utc` instant, or `null` when absent/unparseable. */
private fun String?.toInstantOrNull(): Instant? =
    this?.let { runCatching { Instant.parse(it) }.getOrNull() }

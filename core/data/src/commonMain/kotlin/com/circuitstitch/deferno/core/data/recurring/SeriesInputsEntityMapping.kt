package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.database.sql.SeriesInputsEntity
import com.circuitstitch.deferno.core.database.sql.SeriesOverrideEntity
import com.circuitstitch.deferno.core.model.SeriesInputs
import com.circuitstitch.deferno.core.model.SeriesOverride
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.format.char
import kotlin.time.Instant

/**
 * The row<->domain conversion for the series expansion inputs (#410, ADR-0053 decision 2) — the
 * `SeriesInputsEntityMapping` sibling of `RecurringEntityCodec.kt`, and the reason `seriesInputsEntity`
 * and `seriesOverrideEntity` can stay adapter-free TEXT/INTEGER tables.
 *
 * One codec for every kind, and it always was: both tables are keyed on the item id alone since #422,
 * where they carried a `kind` column beside it before.
 *
 * **Decode is defensive in the same direction the network mapper is.** A row whose anchor or override
 * slot cannot be parsed yields `null` — no inputs — rather than a partially-read grid, because a grid
 * missing an exclusion or a bound is not a smaller grid, it is a wrong one that shows firings which do
 * not exist. `null` already has a precise meaning here ("this device cannot reproduce that grid"), and
 * a cache row this build cannot read is exactly that.
 */
internal fun SeriesInputsEntity.toDomain(overrides: List<SeriesOverrideEntity>): SeriesInputs? {
    val anchor = anchor_local.toLocalDateTimeOrNull() ?: return null
    val storedUntil = until_utc
    val until = if (storedUntil == null) null else storedUntil.toInstantOrNull() ?: return null
    val exdateList = exdates.decodeNewlineList().map { it.toLocalDateTimeOrNull() ?: return null }
    val overrideList = overrides.map { it.toDomainOrNull() ?: return null }
    return SeriesInputs(
        anchorLocal = anchor,
        tzid = tzid,
        untilUtc = until,
        exdates = exdateList,
        // The query already sorts by `recurrence_id`, which is chronological for an ISO wall time — the
        // wire's own "ascending by recurrence_id" guarantee, restored by the index rather than a re-sort.
        overrides = overrideList,
    )
}

private fun SeriesOverrideEntity.toDomainOrNull(): SeriesOverride? {
    val slot = recurrence_id.toLocalDateTimeOrNull() ?: return null
    val storedMoved = moved_to_local
    val moved = if (storedMoved == null) null else storedMoved.toLocalDateTimeOrNull() ?: return null
    return SeriesOverride(recurrenceId = slot, isCancelled = is_cancelled != 0L, movedToLocal = moved)
}

/**
 * The parent row for one item's inputs. [itemId] is the recurring **item** id — never the series id,
 * which names the series but is no item's id (#380).
 */
internal fun SeriesInputs.toEntity(itemId: String) = SeriesInputsEntity(
    item_id = itemId,
    anchor_local = anchorLocal.toWireString(),
    tzid = tzid,
    until_utc = untilUtc?.toString(),
    exdates = exdates.map { it.toWireString() }.encodeNewlineList(),
)

/** The child rows for the same item — one per exception, written as a set (clear then re-seed). */
internal fun SeriesInputs.toOverrideEntities(itemId: String) = overrides.map {
    SeriesOverrideEntity(
        item_id = itemId,
        recurrence_id = it.recurrenceId.toWireString(),
        is_cancelled = if (it.isCancelled) 1L else 0L,
        moved_to_local = it.movedToLocal?.toWireString(),
    )
}

/**
 * A wall time in the spelling the **backend** uses: seconds always present, never `2026-08-26T18:30`.
 *
 * `LocalDateTime.toString()` omits zero seconds, which is valid ISO-8601 and which this client happily
 * reads back — so the omission is invisible until the value leaves the device. It is not survivable
 * there: `chrono`'s `NaiveDateTime::from_str` spells the seconds field as a **mandatory**
 * `Literal(":") + Numeric(Second)` (chrono 0.4, `src/naive/datetime/mod.rs`), so a minute-precision wall
 * time is a hard parse error server-side. A Backup file carrying one would restore its recurring items
 * as errors — the same class of unrestorable-export bug #382 found in the cadence tag, discovered here
 * before it shipped rather than after.
 *
 * Storage uses it too, though nothing there demands it: a column mixing both spellings still sorts
 * correctly (a prefix sorts first, which happens to be right for `:00`), but "happens to be right" is a
 * property no one should have to re-derive when they touch `ORDER BY recurrence_id`.
 */
internal fun LocalDateTime.toWireString(): String = WIRE_WALL_TIME.format(this)

private val WIRE_WALL_TIME = LocalDateTime.Format {
    date(LocalDate.Formats.ISO)
    char('T')
    hour(); char(':'); minute(); char(':'); second()
}

/** Parses a stored local wall time (`2026-08-10T23:59:59`), or `null` when unparseable (defensive). */
private fun String.toLocalDateTimeOrNull(): LocalDateTime? =
    runCatching { LocalDateTime.parse(this) }.getOrNull()

private fun String.toInstantOrNull(): Instant? = runCatching { Instant.parse(this) }.getOrNull()

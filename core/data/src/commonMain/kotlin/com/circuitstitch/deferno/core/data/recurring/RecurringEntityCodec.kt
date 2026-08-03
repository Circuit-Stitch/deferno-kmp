package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.OccurrenceState
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * Shared row<->domain codec helpers for the recurring caches (Habit/Chore/Event/Occurrence, #71).
 * Factored out of the three near-identical entity mappings so the encoding rules (the `\n`-joined
 * lists, the boolean<->INTEGER, the **defensive** enum decode that degrades an unrecognised stored
 * token rather than throwing) live in one place — the same rules `TaskEntityMapping.kt` uses, kept
 * symmetric with the adapter-free `.sq` column types.
 */

/** `[]` -> `""`, else the elements joined with `\n` (the list columns never contain newlines). */
fun List<String>.encodeNewlineList(): String = joinToString("\n")

/** `""` -> `[]` (not `[""]`), else the `\n`-split elements. */
fun String.decodeNewlineList(): List<String> = if (isEmpty()) emptyList() else split("\n")

/** Parses a stored RFC3339 timestamp, or `null` when the column is null. */
fun String?.toInstantOrNull(): Instant? = this?.let(Instant::parse)

/** Parses a stored "HH:MM[:SS]" time-of-day, or `null` when absent/unparseable (#348, defensive). */
fun String?.toLocalTimeOrNull(): LocalTime? =
    this?.let { runCatching { LocalTime.parse(it) }.getOrNull() }

/** Defensive decode: an unrecognised stored token degrades to [DefinitionState.Active] (never throws). */
fun String.toDefinitionStateOrDefault(): DefinitionState =
    DefinitionState.entries.firstOrNull { it.name == this } ?: DefinitionState.Active

/** Defensive decode: an unrecognised stored token degrades to [OccurrenceState.Scheduled]. */
fun String.toOccurrenceStateOrDefault(): OccurrenceState =
    OccurrenceState.entries.firstOrNull { it.name == this } ?: OccurrenceState.Scheduled

/** Defensive decode: an unrecognised stored token degrades to [RecurrenceFrequency.Unknown]. */
fun String.toRecurrenceFrequencyOrDefault(): RecurrenceFrequency =
    RecurrenceFrequency.entries.firstOrNull { it.name == this } ?: RecurrenceFrequency.Unknown

/**
 * The flat column projection of a domain [Recurrence] (#382) — the shared shape the three recurring
 * tables all persist, so the fourteen-column encode/decode is written **once** here rather than
 * triplicated across `{Habit,Chore,Event}EntityMapping.kt` (the generated entity types share no
 * supertype, so a value holder is the only way to factor it out).
 *
 * `Long?` / `String?` throughout because these are the raw SQL primitives the adapter-free `.sq` tables
 * declare; the Kotlin-side widening to `Int?` happens in [decodeRecurrence].
 */
data class RecurrenceColumns(
    val type: String? = null,
    val days: String = "",
    val interval: Long? = null,
    val anchorType: String? = null,
    val anchorDay: Long? = null,
    val anchorNth: Long? = null,
    val anchorWeekday: String? = null,
    val month: Long? = null,
    val day: Long? = null,
    val rrule: String? = null,
    val endType: String? = null,
    val endDate: String? = null,
    val endCount: Long? = null,
    val rawType: String? = null,
)

/** Domain [Recurrence] -> its flat columns; a definition with no rule encodes to all-NULL + `""` days. */
fun Recurrence?.encodeColumns(): RecurrenceColumns {
    val rule = this ?: return RecurrenceColumns()
    val anchor = rule.monthlyAnchor
    return RecurrenceColumns(
        type = rule.frequency.name,
        days = rule.days.encodeNewlineList(),
        interval = rule.interval?.toLong(),
        anchorType = when (anchor) {
            null -> null
            is MonthlyAnchor.DayOfMonth -> ANCHOR_DAY_OF_MONTH
            is MonthlyAnchor.NthWeekday -> ANCHOR_NTH_WEEKDAY
        },
        anchorDay = (anchor as? MonthlyAnchor.DayOfMonth)?.day?.toLong(),
        anchorNth = (anchor as? MonthlyAnchor.NthWeekday)?.nth?.toLong(),
        anchorWeekday = (anchor as? MonthlyAnchor.NthWeekday)?.weekday,
        month = rule.month?.toLong(),
        day = rule.day?.toLong(),
        rrule = rule.rrule,
        // The Never bound stores NULL — mirroring the wire, where an absent `end` key IS never.
        endType = when (rule.bound) {
            RecurrenceBound.Never -> null
            is RecurrenceBound.OnDate -> BOUND_ON_DATE
            is RecurrenceBound.AfterCount -> BOUND_AFTER_COUNT
        },
        endDate = (rule.bound as? RecurrenceBound.OnDate)?.date?.toString(),
        endCount = (rule.bound as? RecurrenceBound.AfterCount)?.n?.toLong(),
        rawType = rule.rawType,
    )
}

/**
 * The flat columns -> domain [Recurrence]; a NULL [type] column is a definition with **no rule** and
 * decodes to `null` (unchanged behaviour).
 *
 * Every sub-decode degrades rather than throws, matching this file's house style: an unrecognised
 * frequency becomes [RecurrenceFrequency.Unknown], an unrecognised or half-populated anchor becomes
 * `null` (a monthly rule that cannot say which day is still a usable monthly rule), and an unrecognised
 * or half-populated bound becomes [RecurrenceBound.Never]. A pre-migration row — every new column NULL —
 * therefore decodes to exactly the rule it already held, which is why the migration needs no back-fill.
 */
@Suppress("LongParameterList")
fun decodeRecurrence(columns: RecurrenceColumns): Recurrence? {
    val type = columns.type ?: return null
    return Recurrence(
        frequency = type.toRecurrenceFrequencyOrDefault(),
        days = columns.days.decodeNewlineList(),
        interval = columns.interval?.toInt(),
        monthlyAnchor = decodeMonthlyAnchor(columns),
        month = columns.month?.toInt(),
        day = columns.day?.toInt(),
        rrule = columns.rrule,
        bound = decodeRecurrenceBound(columns),
        rawType = columns.rawType,
    )
}

private const val ANCHOR_DAY_OF_MONTH = "DayOfMonth"
private const val ANCHOR_NTH_WEEKDAY = "NthWeekday"
private const val BOUND_ON_DATE = "OnDate"
private const val BOUND_AFTER_COUNT = "AfterCount"

/** Defensive decode of the monthly `on` anchor; an unknown or half-populated anchor degrades to `null`. */
private fun decodeMonthlyAnchor(columns: RecurrenceColumns): MonthlyAnchor? = when (columns.anchorType) {
    ANCHOR_DAY_OF_MONTH -> columns.anchorDay?.let { MonthlyAnchor.DayOfMonth(it.toInt()) }
    ANCHOR_NTH_WEEKDAY -> {
        val nth = columns.anchorNth
        val weekday = columns.anchorWeekday
        if (nth != null && weekday != null) MonthlyAnchor.NthWeekday(nth.toInt(), weekday) else null
    }
    else -> null
}

/**
 * Defensive decode of the `end` bound. A NULL `end_type` is the [RecurrenceBound.Never] bound — the
 * correct reading, not merely a safe fallback, since that is how the wire spells it too. An unknown
 * token, or a bound whose payload column is missing, also degrades to Never rather than throwing.
 */
private fun decodeRecurrenceBound(columns: RecurrenceColumns): RecurrenceBound = when (columns.endType) {
    BOUND_ON_DATE ->
        columns.endDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?.let(RecurrenceBound::OnDate)
            ?: RecurrenceBound.Never
    BOUND_AFTER_COUNT -> columns.endCount?.let { RecurrenceBound.AfterCount(it.toInt()) } ?: RecurrenceBound.Never
    else -> RecurrenceBound.Never
}

/** Defensive decode: an unrecognised stored token degrades to [HydrationState.Summary]. */
fun String.toHydrationStateOrDefault(): HydrationState =
    HydrationState.entries.firstOrNull { it.name == this } ?: HydrationState.Summary

/**
 * Defensive decode of the stored [Priority] enum name (#375). Both an unrecognised token *and* a NULL
 * column degrade to [Priority.Default] — and for this field NULL is not merely a safe fallback but the
 * correct reading: the 17->18 migration adds the column without a back-fill, and a row the server sent
 * before the field existed **is** `Normal` (the backend's own `#[serde(default)]`). So a pre-migration
 * cache reads identically to an explicitly-normal one, and nothing needs repopulating first.
 */
fun String?.toPriorityOrDefault(): Priority =
    Priority.entries.firstOrNull { it.name == this } ?: Priority.Default

package com.circuitstitch.deferno.core.data.recurring

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant

/**
 * Shared row<->domain codec helpers for the recurring definition caches (Habit/Chore/Event, #71).
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

/**
 * Defensive decode: an unrecognised stored token degrades to [DefinitionState.Active], never throws.
 *
 * The receiver is nullable because `definition_state` is a nullable column on the one item table — a
 * Task has no light switch — and a NULL reaching here means a recurring row written before this build.
 * A caller that must tell "no light switch" apart from "an unreadable one" reads the column itself; the
 * definition-state source does exactly that.
 */
fun String?.toDefinitionStateOrDefault(): DefinitionState =
    DefinitionState.entries.firstOrNull { it.name == this } ?: DefinitionState.Active

// There is deliberately no `toOccurrenceStateOrDefault` here any more (#390, ADR-0053 decision 4).
// An OccurrenceState is a render-time *reading*, so it is never a column and there is nothing to
// decode: the stored half of a firing is an OccurrenceResolution, decoded by the occurrence fact
// mapping alongside the table that holds it.

/**
 * The flat column projection of a domain [Recurrence] (#382) — the shared shape the three recurring
 * tables all persist, so the encode/decode between the SQL columns and the sealed [Cadence] is written
 * **once** here rather than triplicated across `{Habit,Chore,Event}EntityMapping.kt` (the generated
 * entity types share no supertype, so a value holder is the only way to factor it out).
 *
 * It stays a flat fourteen-field bag on purpose: it mirrors the **columns**, not the domain. Which of
 * them are meaningful is decided by [type], exactly as in the `.sq` files — the union-of-all-cadences
 * shape a set of adapter-free primitive columns forces, and the reason a codec has to exist at all.
 *
 * `Long?` / `String?` throughout because these are the raw SQL primitives the adapter-free `.sq` tables
 * declare; the Kotlin-side widening to `Int` happens in [decodeRecurrence].
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

/**
 * Domain [Recurrence] -> its flat columns; a definition with no rule encodes to all-NULL + `""` days.
 *
 * Each [Cadence] variant writes only the columns it owns and leaves the rest NULL, which is what makes
 * the decode below unambiguous. The bound is layered on afterwards rather than repeated seven times:
 * it is orthogonal to the cadence, so every variant can carry any of the three.
 */
fun Recurrence?.encodeColumns(): RecurrenceColumns {
    val rule = this ?: return RecurrenceColumns()
    val cadenceColumns = when (val cadence = rule.cadence) {
        Cadence.Daily -> RecurrenceColumns(type = CADENCE_DAILY)
        is Cadence.EveryNDays -> RecurrenceColumns(type = CADENCE_EVERY_N_DAYS, interval = cadence.n.toLong())
        is Cadence.Weekly -> RecurrenceColumns(type = CADENCE_WEEKLY, days = cadence.days.encodeNewlineList())
        is Cadence.Monthly -> {
            val anchor = cadence.on
            RecurrenceColumns(
                type = CADENCE_MONTHLY,
                interval = cadence.interval.toLong(),
                anchorType = when (anchor) {
                    null -> null
                    is MonthlyAnchor.DayOfMonth -> ANCHOR_DAY_OF_MONTH
                    is MonthlyAnchor.NthWeekday -> ANCHOR_NTH_WEEKDAY
                },
                anchorDay = (anchor as? MonthlyAnchor.DayOfMonth)?.day?.toLong(),
                anchorNth = (anchor as? MonthlyAnchor.NthWeekday)?.nth?.toLong(),
                anchorWeekday = (anchor as? MonthlyAnchor.NthWeekday)?.weekday,
            )
        }
        is Cadence.Yearly -> RecurrenceColumns(
            type = CADENCE_YEARLY,
            interval = cadence.interval.toLong(),
            month = cadence.month.toLong(),
            day = cadence.day.toLong(),
        )
        is Cadence.Custom -> RecurrenceColumns(type = CADENCE_CUSTOM, rrule = cadence.rrule)
        // The cadence itself is unnameable here, so the row carries the placeholder token AND the wire
        // token it preserved; the decode hands the latter straight back.
        is Cadence.Unmodelled -> RecurrenceColumns(type = CADENCE_UNMODELLED, rawType = cadence.rawType)
    }
    return cadenceColumns.copy(
        // The Never bound stores NULL — mirroring the wire, where an absent `end` key IS never.
        endType = when (rule.bound) {
            RecurrenceBound.Never -> null
            is RecurrenceBound.OnDate -> BOUND_ON_DATE
            is RecurrenceBound.AfterCount -> BOUND_AFTER_COUNT
        },
        endDate = (rule.bound as? RecurrenceBound.OnDate)?.date?.toString(),
        endCount = (rule.bound as? RecurrenceBound.AfterCount)?.n?.toLong(),
    )
}

/**
 * The flat columns -> domain [Recurrence]; a NULL [RecurrenceColumns.type] column is a definition with
 * **no rule** and decodes to `null` (unchanged behaviour).
 *
 * Every sub-decode degrades rather than throws, matching this file's house style: an unrecognised
 * cadence token becomes [Cadence.Unmodelled] under whichever name the row still has (see the arm), an
 * unrecognised or half-populated anchor becomes `null` (a monthly rule that cannot say which day is
 * still a usable monthly rule), an absent cycle reads as `1` (the wire's own default), and an
 * unrecognised or half-populated bound becomes [RecurrenceBound.Never]. A pre-migration row — every
 * added column NULL — therefore still decodes to the rule it already held, which is why the migration
 * needs no back-fill.
 */
fun decodeRecurrence(columns: RecurrenceColumns): Recurrence? {
    val type = columns.type ?: return null
    val cadence = when (type) {
        CADENCE_DAILY -> Cadence.Daily
        CADENCE_EVERY_N_DAYS -> Cadence.EveryNDays(columns.interval?.toInt() ?: 1)
        CADENCE_WEEKLY -> Cadence.Weekly(columns.days.decodeNewlineList())
        CADENCE_MONTHLY -> Cadence.Monthly(columns.interval?.toInt() ?: 1, decodeMonthlyAnchor(columns))
        CADENCE_YEARLY -> Cadence.Yearly(
            interval = columns.interval?.toInt() ?: 1,
            month = columns.month?.toInt() ?: 1,
            day = columns.day?.toInt() ?: 1,
        )
        CADENCE_CUSTOM -> Cadence.Custom(columns.rrule.orEmpty())
        // The placeholder is not a cadence name — the real one is in `raw_type`. A row that has none
        // keeps a BLANK token, which the Backup export reads as "skip this rule"; re-emitting the
        // literal "Unknown" as a wire `type` would be the very destruction #382 exists to stop.
        CADENCE_UNMODELLED -> Cadence.Unmodelled(columns.rawType.orEmpty())
        // Any other token is a cadence some newer client named and this build has never heard of. The
        // token itself is all there is to keep, so it becomes the name.
        else -> Cadence.Unmodelled(columns.rawType ?: type)
    }
    return Recurrence(cadence = cadence, bound = decodeRecurrenceBound(columns))
}

/**
 * The stored `recurrence_type` tokens. **A PERSISTED FORMAT, not a display string** — these are the
 * names of the enum this codec used to decode into, and every cache written by an earlier build still
 * holds them, so renaming one silently strips the rule off every cached row of that cadence.
 * [CADENCE_UNMODELLED] keeps its historical `"Unknown"` spelling for exactly that reason.
 */
private const val CADENCE_DAILY = "Daily"
private const val CADENCE_EVERY_N_DAYS = "EveryNDays"
private const val CADENCE_WEEKLY = "Weekly"
private const val CADENCE_MONTHLY = "Monthly"
private const val CADENCE_YEARLY = "Yearly"
private const val CADENCE_CUSTOM = "Custom"
private const val CADENCE_UNMODELLED = "Unknown"

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

package com.circuitstitch.deferno.core.model

import com.circuitstitch.deferno.core.model.corpus.RecurrenceCorpus
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Instant

/**
 * The golden corpus that pins [expandOccurrenceGrid] against the Rust (ADR-0053 decision 5, #401).
 *
 * **Generated, never hand-authored.** Every expectation in `contracts/recurrence-corpus/` came out of
 * `Recurrence::to_rrule(tz)` → `defernodate::expand_series` — the same path the occurrence handlers
 * use — via `backend/examples/dump_recurrence_corpus.rs` in `Circuit-Stitch/Deferno`, whose CI fails
 * if the committed corpus has drifted from what the code now produces. A hand-written expectation
 * table here would be a second specification, which is the thing this file exists to prevent.
 *
 * **Direction of authority.** Where a case and the expander disagree, *the expander is wrong*. Where
 * the case itself is wrong, the fix lands in Rust and the corpus regenerates. Editing a case file to
 * make Kotlin pass converts the corpus into the very thing it replaced.
 *
 * Deliberately NOT covered here, because they are client-side policy rather than grid facts: which
 * [ExpansionRefusal] arm a refusal takes, and the `Custom` / `Unmodelled` / anchorless-`Monthly`
 * refusals the server has no opinion about. Those live in [OccurrenceGridTest].
 */
class RecurrenceCorpusTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun everyCorpusCaseMatchesTheExpander() {
        val failures = mutableListOf<String>()
        for ((fileName, raw) in RecurrenceCorpus.ALL.entries.sortedBy { it.key }) {
            val case = json.parseToJsonElement(raw).jsonObject
            val actual = runCatching { render(expand(case)) }
                .getOrElse { listOf("THREW: ${it::class.simpleName}: ${it.message}") }
            val expected = expected(case)
            if (actual != expected) {
                failures += buildString {
                    appendLine("── $fileName")
                    appendLine("   ${case.str("note")}")
                    appendLine("   rrule:    ${case.str("rrule")}")
                    appendLine("   expected: $expected")
                    appendLine("   actual:   $actual")
                }
            }
        }
        if (failures.isNotEmpty()) {
            fail(
                "${failures.size} of ${RecurrenceCorpus.ALL.size} corpus cases disagree with the " +
                    "expander. The corpus is normative — fix the expander, not the case file " +
                    "(ADR-0053 decision 5).\n\n" + failures.joinToString("\n"),
            )
        }
    }

    /**
     * A guard against the corpus silently shrinking. Each name below is one line of #401's semantics
     * checklist; a case deleted upstream must fail here rather than quietly stop being pinned.
     *
     * This asserts only that a case *exists* — never what it contains, which stays generated.
     */
    @Test
    fun everyPinnedSemanticStillHasACase() {
        val required = listOf(
            "monthly-day-of-month-skips-short-months.json",
            "monthly-interval-two-skipped-month-still-consumes-the-interval.json",
            "yearly-twenty-ninth-of-february-skips.json",
            "monthly-nth-weekday-last-friday.json",
            "every-n-days-stride-phase-is-the-anchor.json",
            "weekly-emits-no-interval-and-no-wkst.json",
            "bound-until-is-inclusive-of-the-bound-day.json",
            // The case that actually exercises the inclusivity: every other bound case fires at
            // 09:00, where inclusive and exclusive agree. Mutation testing found this gap.
            "bound-until-firing-exactly-on-the-sentinel.json",
            "segment-until-utc-is-exclusive.json",
            "exdate-still-consumes-a-count.json",
            "override-moves-and-cancels.json",
            "dst-spring-forward-wall-time-inside-the-gap.json",
            "dst-fall-back-wall-time-inside-the-fold.json",
            "dst-thirty-minute-gap-shifts-by-thirty-minutes.json",
            "dst-midnight-gap-drops-the-whole-day-santiago.json",
            "bound-count-dst-dropped-slot-does-not-consume.json",
        )
        val missing = required - RecurrenceCorpus.ALL.keys
        assertEquals(emptyList(), missing, "corpus cases went missing; regenerate from the Rust")
        assertTrue(RecurrenceCorpus.ALL.size >= required.size, "the corpus is suspiciously small")
    }

    // ── Reading a case ────────────────────────────────────────────────────────────────────────────

    private fun expand(case: JsonObject): Expansion {
        val series = case.getValue("series").jsonObject
        val window = case.getValue("window").jsonObject
        return expandOccurrenceGrid(
            recurrence = case.getValue("recurrence").jsonObject.toRecurrence(),
            series = SeriesInputs(
                anchorLocal = LocalDateTime.parse(series.str("dtstart_local")),
                tzid = series.str("tzid"),
                untilUtc = series["until_utc"]?.takeUnless { it.isNull() }
                    ?.let { Instant.parse(it.jsonPrimitive.content) },
                exdates = series.getValue("exdates").jsonArray
                    .map { LocalDateTime.parse(it.jsonPrimitive.content) },
                overrides = series.getValue("overrides").jsonArray.map { it.jsonObject }.map {
                    SeriesOverride(
                        recurrenceId = LocalDateTime.parse(it.str("recurrence_id")),
                        isCancelled = it.getValue("is_cancelled").jsonPrimitive.boolean,
                        movedToLocal = it["moved_to_local"]?.takeUnless { m -> m.isNull() }
                            ?.let { m -> LocalDateTime.parse(m.jsonPrimitive.content) },
                    )
                },
            ),
            from = LocalDate.parse(window.str("from")),
            to = LocalDate.parse(window.str("to")),
        )
    }

    private fun expected(case: JsonObject): List<String> = when (val outcome = case.str("outcome")) {
        "not_expandable" -> listOf(NOT_EXPANDABLE)
        "firings" -> case.getValue("firings").jsonArray.map { it.jsonObject }.map {
            line(
                recurrenceId = it.str("recurrence_id"),
                startLocal = it.str("start_local"),
                cancelled = it.getValue("is_cancelled").jsonPrimitive.boolean,
                override = it.getValue("is_override").jsonPrimitive.boolean,
            )
        }
        else -> fail("unknown corpus outcome '$outcome' — the generator emits only firings/not_expandable")
    }

    private fun render(expansion: Expansion): List<String> = when (expansion) {
        // The refusal ARM is a client concern the corpus has no opinion on; only the refusal itself
        // is pinned. See the class KDoc.
        is Expansion.NotExpandable -> listOf(NOT_EXPANDABLE)
        is Expansion.Firings -> expansion.firings.map {
            line(it.recurrenceId.toString(), it.startLocal.toString(), it.isCancelled, it.isOverride)
        }
    }

    /**
     * Both sides rendered to the same line so a diff reads as a diff. Seconds are always present
     * because the corpus emits `%Y-%m-%dT%H:%M:%S` while `LocalDateTime.toString()` elides `:00`.
     */
    private fun line(recurrenceId: String, startLocal: String, cancelled: Boolean, override: Boolean) =
        buildString {
            append(withSeconds(recurrenceId))
            if (withSeconds(startLocal) != withSeconds(recurrenceId)) append("->").append(withSeconds(startLocal))
            if (cancelled) append("!cancelled")
            if (override) append("*override")
        }

    private fun withSeconds(iso: String) = if (iso.count { it == ':' } == 1) "$iso:00" else iso

    private fun JsonObject.toRecurrence(): Recurrence = Recurrence(
        cadence = when (val type = str("type")) {
            "daily" -> Cadence.Daily
            "every_n_days" -> Cadence.EveryNDays(int("n"))
            "weekly" -> Cadence.Weekly(getValue("days").jsonArray.map { it.jsonPrimitive.content })
            "monthly" -> Cadence.Monthly(
                interval = int("interval"),
                on = getValue("on").jsonObject.let { on ->
                    when (val anchor = on.str("type")) {
                        "day_of_month" -> MonthlyAnchor.DayOfMonth(on.int("day"))
                        "nth_weekday" -> MonthlyAnchor.NthWeekday(on.int("nth"), on.str("weekday"))
                        else -> fail("unknown monthly anchor '$anchor'")
                    }
                },
            )
            "yearly" -> Cadence.Yearly(int("interval"), int("month"), int("day"))
            else -> fail("unknown cadence '$type' — the corpus does not generate Custom (see the KDoc)")
        },
        bound = this["end"]?.jsonObject?.let { end ->
            when (val type = end.str("type")) {
                "never" -> RecurrenceBound.Never
                "on_date" -> RecurrenceBound.OnDate(LocalDate.parse(end.str("date")))
                "after_count" -> RecurrenceBound.AfterCount(end.int("n"))
                else -> fail("unknown bound '$type'")
            }
        } ?: RecurrenceBound.Never,
    )

    private fun JsonObject.str(key: String) = getValue(key).jsonPrimitive.content
    private fun JsonObject.int(key: String) = getValue(key).jsonPrimitive.int
    private fun kotlinx.serialization.json.JsonElement.isNull() =
        this is kotlinx.serialization.json.JsonNull

    private companion object {
        const val NOT_EXPANDABLE = "<not expandable>"
    }
}

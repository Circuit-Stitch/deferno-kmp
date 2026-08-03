package com.circuitstitch.deferno.feature.tasks.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import com.circuitstitch.deferno.core.designsystem.format.LocalToday
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The recurring row's cadence + next-due subtitle (#384) — the phrase [recurrenceSummary] builds from
 * `Item.recurrence` + the `RecurrenceCursor` reading. One edit covers **Android and desktop**: both
 * shells render the same `ItemTreeContent`, so this is the only Compose home for these assertions.
 *
 * The table walks every `CadenceReading` arm, both non-open-ended `RecurrenceBound` arms and all three
 * `RecurrenceCursor` arms, because each one is a separate string key across five locales and a silently
 * wrong arm looks exactly like a right one.
 *
 * **Phrasing only.** The three RENDERER RULES the string catalog records on the keys themselves — the
 * `EveryNDays(1)` fold, the interval floor, which weekday tokens survive and in what order — belong to
 * `RecurrenceReadingTest` now, because they belong to the shared reading all four platforms consume. What
 * is left here is the half only Compose can get wrong: which key each arm reaches for, and how the three
 * clauses join. The cases below still pass a raw `Cadence` because that is what a row holds; they assert
 * the phrase, not the normalisation that produced it.
 *
 * Two harness notes:
 *  - The summary is Composable (it reads `stringResource`/`pluralStringResource`), so each case is
 *    captured out of a one-shot composition rather than asserted through a rendered node — the phrase
 *    itself is the unit under test, not its placement in the row.
 *  - Locale: the weekday CLDR lookup is pinned to [Locale.ENGLISH] explicitly, while the catalog strings
 *    resolve through the JVM default locale, exactly as the neighbouring Compose tests already assume
 *    (`ItemTreeFilterTest` asserts "In today"). Both are English here; the expectations are English.
 */
@OptIn(ExperimentalTestApi::class)
class RecurrenceSummaryTest {

    /** Pinned "today"; every fixture instant below is read against it in [zone], never the wall clock. */
    private val today = LocalDate(2026, 6, 15)

    /** UTC so an `Instant` → day resolution can't drift with the machine's zone. */
    private val zone = TimeZone.UTC

    // --- fixtures ---

    /**
     * A **live** recurring definition: rule + cursor, so the reading is `DueOn` and the row gets its
     * "Next: …" clause. [cursorAt] `null` is the *exhausted* series (the server clears the cursor when a
     * bound is reached), not "no deadline" — see `RecurrenceCursor`.
     */
    private fun habit(
        cadence: Cadence,
        bound: RecurrenceBound = RecurrenceBound.Never,
        cursorAt: Instant? = Instant.parse("2026-06-16T12:00:00Z"),
    ) = Item(
        id = "h-1",
        kind = ItemKind.Habit,
        title = "Water the plants",
        recurrence = Recurrence(cadence, bound),
        recurrenceCursorAt = cursorAt,
    )

    /**
     * An **Archived** definition — the one shape that shows a rule with no next-due clause at all, since
     * a switched-off definition keeps a stale cursor the reading must refuse to trust (`archive_habit`
     * "doesn't touch complete_by"). Used to isolate the cadence half of the phrase.
     */
    private fun cadenceOnly(cadence: Cadence, bound: RecurrenceBound = RecurrenceBound.Never) = Item(
        id = "h-2",
        kind = ItemKind.Habit,
        title = "Stretch",
        definitionState = DefinitionState.Archived,
        recurrence = Recurrence(cadence, bound),
        recurrenceCursorAt = Instant.parse("2026-01-04T12:00:00Z"),
    )

    /**
     * Captures the phrase out of a one-shot composition. "Today" is pinned through `LocalToday` rather
     * than a parameter — [recurrenceSummary] takes [zone] as its ONLY date input and derives the day from
     * it, so a caller cannot resolve the cursor's instant in one zone and "today" in another. That is the
     * same seam the screenshot goldens pin, and the reason this harness has one fewer knob than the phrase
     * has inputs.
     */
    private fun summaryOf(item: Item): RecurrenceSummary? {
        var captured: RecurrenceSummary? = null
        runComposeUiTest {
            setContent {
                CompositionLocalProvider(LocalToday provides today) {
                    captured = recurrenceSummary(item, zone = zone, locale = Locale.ENGLISH)
                }
            }
            waitForIdle()
        }
        return captured
    }

    private fun textOf(item: Item): String = requireNotNull(summaryOf(item)) { "expected a summary" }.text

    // --- the cadence half ---

    /**
     * Every `Cadence` arm, isolated from the cursor. Note the three deliberately-unrendered parameters:
     * `Monthly.on`, `Yearly.month`/`day` and `Custom.rrule` never reach the row (they need grammar the
     * catalog does not have, or are raw wire text) — #383's detail surface owns them.
     */
    @Test
    fun everyCadenceArmRendersItsOwnPhrase() {
        val cases = listOf(
            Cadence.Daily to "Daily",
            Cadence.EveryNDays(3) to "Every 3 days",
            Cadence.Weekly(listOf("Mon", "Wed")) to "Weekly on Mon, Wed",
            Cadence.Monthly(1) to "Monthly",
            Cadence.Monthly(3) to "Every 3 months",
            // The anchor is dropped, not paraphrased: "the 2nd Tuesday" has no key family here (#383).
            Cadence.Monthly(2, MonthlyAnchor.NthWeekday(2, "Tue")) to "Every 2 months",
            Cadence.Monthly(1, MonthlyAnchor.DayOfMonth(15)) to "Monthly",
            Cadence.Yearly(1, 6, 14) to "Yearly",
            Cadence.Yearly(2, 6, 14) to "Every 2 years",
            Cadence.Custom("FREQ=MONTHLY;INTERVAL=3;BYDAY=1SA") to "Custom schedule",
            Cadence.Unmodelled("lunar_phase") to "Repeats",
        )

        for ((cadence, expected) in cases) {
            assertEquals(expected, textOf(cadenceOnly(cadence)), "cadence $cadence")
        }
    }

    /** RENDERER RULE (`tasks_cadence_every_n_days`): a stride of one IS "Daily"; the `one` arm is dead copy. */
    @Test
    fun aStrideOfOneDayNormalisesToDailyRatherThanEveryOneDay() {
        assertEquals("Daily", textOf(cadenceOnly(Cadence.EveryNDays(1))))
    }

    /** RENDERER RULE (`tasks_cadence_weekly`): no readable days is "Weekly", never "Weekly on " dangling. */
    @Test
    fun aWeeklyRuleWithNoReadableDaysFallsBackToTheBareAdverb() {
        assertEquals("Weekly", textOf(cadenceOnly(Cadence.Weekly(emptyList()))))
        assertEquals("Weekly", textOf(cadenceOnly(Cadence.Weekly(listOf("Cinqui", "Sexta")))))
    }

    /** An unknown token is dropped, not rendered and not thrown on — the rest of the week still reads. */
    @Test
    fun anUnknownWeekdayTokenIsDroppedRatherThanShownOrThrownOn() {
        assertEquals("Weekly on Mon, Fri", textOf(cadenceOnly(Cadence.Weekly(listOf("Mon", "Blursday", "Fri")))))
    }

    /**
     * The ISO day numbers the shared reading hands over become CLDR labels in week order. Which tokens
     * survive, deduped and sorted, is `RecurrenceReadingTest`'s to assert — this pins only that the
     * numbers reach `shortWeekdayLabels` index-aligned, which is the half a renderer can get wrong.
     */
    @Test
    fun weekdayNumbersRenderAsLocalizedLabelsInWeekOrder() {
        assertEquals("Weekly on Mon, Wed, Sat", textOf(cadenceOnly(Cadence.Weekly(listOf("Sat", "wed", "Mon")))))
        assertEquals("Weekly on Sun", textOf(cadenceOnly(Cadence.Weekly(listOf("Sun")))))
    }

    /** Neither raw wire payload is ever shown to a user — that is the whole point of both keys. */
    @Test
    fun neitherTheRawRruleNorTheUnmodelledTokenLeaksIntoTheRow() {
        assertTrue("FREQ" !in textOf(cadenceOnly(Cadence.Custom("FREQ=DAILY;COUNT=5"))))
        assertTrue("lunar_phase" !in textOf(cadenceOnly(Cadence.Unmodelled("lunar_phase"))))
    }

    // --- the bound half ---

    @Test
    fun aDatedBoundAppendsAnUntilClause() {
        val bound = RecurrenceBound.OnDate(LocalDate(2026, 6, 14))
        assertEquals("Daily · until Jun 14, 2026", textOf(cadenceOnly(Cadence.Daily, bound)))
    }

    @Test
    fun aCountedBoundAppendsTheFiringCount() {
        assertEquals("Daily · 10 times", textOf(cadenceOnly(Cadence.Daily, RecurrenceBound.AfterCount(10))))
        assertEquals("Daily · 1 time", textOf(cadenceOnly(Cadence.Daily, RecurrenceBound.AfterCount(1))))
    }

    /** `Never` is the default and by far the common case: it must add *nothing*, not an "ongoing" word. */
    @Test
    fun anUnboundedRuleAddsNothingAtAll() {
        assertEquals("Daily", textOf(cadenceOnly(Cadence.Daily, RecurrenceBound.Never)))
    }

    // --- the cursor half ---

    @Test
    fun aLiveSeriesNamesItsNextFiringRelativeToToday() {
        assertEquals("Daily · Next: Tomorrow", textOf(habit(Cadence.Daily)))
        assertEquals(
            "Daily · Next: Today",
            textOf(habit(Cadence.Daily, cursorAt = Instant.parse("2026-06-15T12:00:00Z"))),
        )
    }

    /** A cursor in the PAST is the normal reading for a missed Habit (#277), not corrupt data. */
    @Test
    fun aMissedSeriesReadsBackwardsRatherThanHidingTheRow() {
        assertEquals(
            "Daily · Next: 5 days ago",
            textOf(habit(Cadence.Daily, cursorAt = Instant.parse("2026-06-10T12:00:00Z"))),
        )
    }

    /** A cleared cursor alongside a rule means the bound was reached — emphatically not "no deadline". */
    @Test
    fun aClearedCursorReadsAsAnEndedSeries() {
        assertEquals(
            "Daily · 10 times · Series ended",
            textOf(habit(Cadence.Daily, RecurrenceBound.AfterCount(10), cursorAt = null)),
        )
    }

    /** An archived definition has no *next*, but it is still a weekly Habit — the cadence stays. */
    @Test
    fun anArchivedDefinitionKeepsItsCadenceAndDropsTheNextDueClause() {
        assertEquals("Weekly on Mon", textOf(cadenceOnly(Cadence.Weekly(listOf("Mon")))))
    }

    // --- what a screen reader hears ---

    @Test
    fun theSpokenLineWrapsTheCadenceInItsVerbAndKeepsEverythingElse() {
        val summary = requireNotNull(summaryOf(habit(Cadence.Weekly(listOf("Mon", "Wed")))))

        assertEquals("Weekly on Mon, Wed · Next: Tomorrow", summary.text)
        assertEquals("Repeats Weekly on Mon, Wed · Next: Tomorrow", summary.a11yLabel)
    }

    /**
     * RENDERER RULE (`tasks_recurrence_a11y_prefix`): the `Unmodelled` arm's string already IS the verb
     * phrase the prefix adds — in all five locales — so wrapping it speaks "Repeats Repeats".
     */
    @Test
    fun theUnmodelledArmIsAnnouncedBareRatherThanSpeakingRepeatsRepeats() {
        val summary = requireNotNull(summaryOf(habit(Cadence.Unmodelled("lunar_phase"))))

        assertEquals("Repeats · Next: Tomorrow", summary.a11yLabel)
        assertEquals(summary.text, summary.a11yLabel, "the unmodelled arm needs no separate spoken form")
        assertTrue("Repeats Repeats" !in summary.a11yLabel)
    }

    @Test
    fun theBoundAndNextDueSurviveIntoTheSpokenLine() {
        val summary = requireNotNull(
            summaryOf(habit(Cadence.Daily, RecurrenceBound.OnDate(LocalDate(2026, 12, 31)))),
        )

        // clearAndSetSemantics REPLACES the visible text, so anything missing here is simply not announced.
        assertEquals("Repeats Daily · until Dec 31, 2026 · Next: Tomorrow", summary.a11yLabel)
    }

    // --- the non-recurring control ---

    /** A Task carries no rule, so it renders no subtitle — its row must be byte-identical to before. */
    @Test
    fun aTaskWithNoRuleRendersNothingAtAll() {
        assertNull(summaryOf(Item(id = "t-1", kind = ItemKind.Task, title = "Water the plants")))
    }

    /**
     * A Task's `completeBy` is a deadline, never a series cursor — and it is deliberately not projected
     * onto `Item` at all. Even a recurring-kind row whose rule did not survive the wire says nothing:
     * the rule is the discriminator, the cursor is only the value.
     */
    @Test
    fun aRecurringKindWhoseRuleDidNotSurviveTheWireAlsoRendersNothing() {
        assertNull(
            summaryOf(
                Item(
                    id = "h-3",
                    kind = ItemKind.Habit,
                    title = "Morning run",
                    recurrenceCursorAt = Instant.parse("2026-06-16T12:00:00Z"),
                ),
            ),
        )
    }
}

package com.circuitstitch.deferno.core.model

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock

/**
 * What a recurring [Item]'s **cursor** currently says — the pure reading over [Item.recurrence] +
 * [Item.completeBy] that an [Item tree] row or a detail ribbon renders (#384). A typed code, not a
 * string: per CLAUDE.md's localization rule the View maps it to a localized phrase (Compose via
 * `Res.string`, Swift via `L`), so this type stays iOS-safe and needs no parity override.
 *
 * **On a recurring definition `complete_by` is a moving cursor, not an upper bound** — the backend's
 * `2026-06-02-recurrence-anchor-and-bound` ADR. The series is anchored once at the chosen start
 * (frozen as the RRULE's `DTSTART`) and the live `complete_by` walks forward as occurrences are
 * resolved; the *bound* lives on the rule instead, as [RecurrenceBound] (`UNTIL`/`COUNT`). Three
 * consequences follow, and this type exists because the client could previously express none of them
 * — #275 (due next month) and #277 (overdue since July) rendered as the same bare title:
 *
 * 1. **A past cursor is normal, not corrupt.** A [Habit]'s cursor only advances on mark-done, so
 *    "overdue since July" is the honest reading of one that has been missed. [DueOn] therefore
 *    carries a [RelativeDay] that may point backwards, and the renderer — not this reading — decides
 *    how loudly to say so.
 * 2. **A cleared cursor means the series ran out.** When the bound is reached the server clears
 *    `complete_by` (`ScheduleAdvance::Ended`), so a rule with no cursor is [Exhausted] — emphatically
 *    *not* "no deadline set", which is what a naive null-check would render.
 * 3. **Neither field alone can tell those apart**, which is precisely why [Item] carries both. The
 *    *rule* is the discriminator (is this a series at all?) and the *cursor* is the value: an absent
 *    cursor reads [Exhausted] only in the presence of a rule, and [NoCursor] without one.
 * 4. **[Exhausted] is the server's word, not a proof.** It mirrors `ScheduleAdvance::Ended`, which the
 *    backend also raises when its 400-day lookahead (`next_scheduled_date_after`) finds nothing — so an
 *    *unbounded* rule firing less often than that (a two-yearly inspection) can read [Exhausted] while
 *    still being live. Keep the rendered phrase factual ("Series ended"), never absolute ("never again").
 *
 * Two nulls that are **not** exhaustion, for the same reason: `ScheduleAdvance::LeaveAlone` leaves the
 * cursor untouched when the rule cannot be read, and legacy Habit/Chore rows predating the field were
 * backfilled server-side. Both still read correctly here — but neither a stale cursor nor a far-past
 * [DueOn] is evidence about what the *user* did.
 */
sealed interface RecurrenceCursor {

    /**
     * No cursor to read — a [Task] (whose `completeBy` is a plain deadline, never a series cursor), a
     * recurring definition whose rule did not survive the wire, or an **Archived** definition (switched
     * off, so it has no *next* even though the server keeps its cursor). There is nothing to say about
     * where the series is going, though the rule itself may still be worth rendering.
     */
    data object NoCursor : RecurrenceCursor

    /**
     * The rule is present but its cursor has been cleared: the series hit its [RecurrenceBound] and
     * will not fire again. Distinct from [NoCursor] — this item *is* recurring, it is simply done
     * recurring, and a surface may want to say so (the webui shows nothing at all for this case).
     */
    data object Exhausted : RecurrenceCursor

    /**
     * The series is live and next due on [day], read relative to today. [day] may be in the past
     * (see the type KDoc: a missed Habit's cursor sits where it stopped advancing) — "overdue" is a
     * rendering of a past [RelativeDay], not a fourth variant here.
     */
    data class DueOn(val day: RelativeDay) : RecurrenceCursor
}

/**
 * The [RecurrenceCursor] reading for this [Item] against [today], resolving [Item.completeBy] to a
 * calendar day in [zone]. Delegates the day math to [relativeDay] rather than repeating it.
 *
 * **Call this at render time; never bake it into repository state.** `ItemRepository.observeItems()`
 * is a cold `Flow` over the local caches (ADR-0001) that only re-emits when the *database* changes —
 * so a [RecurrenceCursor] computed at emit time would still claim "Tomorrow" tomorrow, and would keep
 * claiming it until some unrelated write happened to nudge the store. Nothing about a clock tick
 * reaches that Flow. The reading is cheap and pure; derive it where it is displayed.
 *
 * Both [zone] and [today] are parameters so a test can pin them (a test on the real clock rots) and
 * so a caller with a better answer than the device's can supply one. Note that the device zone is
 * **not** always the account zone the server scheduled against — #392 tracks that divergence; this
 * follows the existing [relativeDay] convention rather than pre-empting it.
 *
 * [today] **derives from [zone]**, deliberately: supplying only a non-device [zone] must not leave
 * "today" resolved in the device's. That is exactly the call shape #392 will use, and the mismatch
 * would silently shift the reading by a day for anyone near a date boundary.
 */
fun Item.recurrenceCursor(
    zone: TimeZone = TimeZone.currentSystemDefault(),
    today: LocalDate = Clock.System.todayIn(zone),
): RecurrenceCursor = when {
    recurrence == null -> RecurrenceCursor.NoCursor
    // An Archived definition is switched off, but the server leaves its cursor exactly where it stopped
    // (`archive_habit`: "archive doesn't touch complete_by/series_id" — it vacates the due/series indexes
    // instead). So the stale cursor outlives the archive forever, and reading it would tell a user that
    // something they switched off months ago is "overdue since July". The rule still renders — an
    // Archived Habit is still a weekly Habit — but it has no *next*, which is exactly [NoCursor].
    definitionState == DefinitionState.Archived -> RecurrenceCursor.NoCursor
    recurrenceCursorAt == null -> RecurrenceCursor.Exhausted
    else -> RecurrenceCursor.DueOn(relativeDay(recurrenceCursorAt, zone, today))
}

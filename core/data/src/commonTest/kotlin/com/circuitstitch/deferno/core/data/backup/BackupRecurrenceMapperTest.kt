package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.RecurrenceFrequency
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.mapper.toDomain
import kotlinx.datetime.LocalDate
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The Backup file's recurrence round-trip (#382, ADR-0041). The export mapper writes `ItemView`, the
 * import path reads it back through `core:network`'s own read mapper, so a rule must survive
 * **domain → wire → JSON → wire → domain** byte-for-byte or a restore silently changes the user's rule.
 *
 * It also pins the fix for an unreported second-order bug. The old export emitted `type = null` for a
 * cadence it could not name, and `explicitNulls = false` turned that into a body with **no `type` key**
 * — which the backend's internally-tagged `Cadence` rejects. Since `every_n_days` and `custom` both
 * collapsed to `Unknown` on read, a backup of such an item was not merely lossy, it was
 * **unrestorable**. The chosen resolution is explicit and asserted here: **skip on export** (a rule with
 * no nameable token is omitted rather than exported tagless) and **named placeholder on import** (an
 * absent cadence restores as `daily`, because `POST /habits` requires one).
 */
class BackupRecurrenceMapperTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun habitWith(rule: Recurrence?) = Habit(
        id = HabitId("h-1"),
        orgSlug = "u-e4h2qk",
        title = "stretch",
        definitionState = DefinitionState.Active,
        recurrence = rule,
        dateCreated = created,
    )

    /** domain → `ItemView` → JSON → `ItemView` → domain, exactly as export-then-import does it. */
    private fun roundTrip(rule: Recurrence): Recurrence? {
        val exported = habitWith(rule).toItemView()
        val json = DefernoJson.encodeToString(ItemView.serializer(), exported)
        val reread = DefernoJson.decodeFromString(ItemView.serializer(), json)
        return (reread as ItemView.Habit).recurrence.toDomain()
    }

    @Test
    fun everySixCadencesSurviveTheFullExportImportRoundTripUnchanged() {
        // Before the widening, four of these six came back wrong: every_n_days and custom collapsed to
        // Unknown outright, and monthly/yearly kept their name but lost every parameter.
        val rules = listOf(
            Recurrence(RecurrenceFrequency.Daily),
            Recurrence(RecurrenceFrequency.EveryNDays, interval = 30),
            Recurrence(RecurrenceFrequency.Weekly, days = listOf("Mon", "Wed")),
            Recurrence(
                RecurrenceFrequency.Monthly,
                interval = 2,
                monthlyAnchor = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri"),
            ),
            Recurrence(
                RecurrenceFrequency.Monthly,
                interval = 1,
                monthlyAnchor = MonthlyAnchor.DayOfMonth(15),
            ),
            Recurrence(RecurrenceFrequency.Yearly, interval = 1, month = 6, day = 14),
            Recurrence(RecurrenceFrequency.Custom, rrule = "FREQ=WEEKLY;BYDAY=MO,WE"),
        )

        for (rule in rules) assertEquals(rule, roundTrip(rule), "round-trip of $rule")
    }

    @Test
    fun allThreeEndBoundsSurviveTheRoundTripAndNeverEmitsNoEndKey() {
        val base = Recurrence(RecurrenceFrequency.EveryNDays, interval = 3)

        assertEquals(base, roundTrip(base), "the default Never bound")
        val untilBound = base.copy(bound = RecurrenceBound.OnDate(LocalDate(2027, 1, 31)))
        assertEquals(untilBound, roundTrip(untilBound))
        val countBound = base.copy(bound = RecurrenceBound.AfterCount(10))
        assertEquals(countBound, roundTrip(countBound))

        // Never must emit NO `end` key — that is how the server itself spells it (its Serialize skips
        // the key), so an explicit {"type":"never"} would be a shape the backend never produces.
        val neverJson = DefernoJson.encodeToString(ItemView.serializer(), habitWith(base).toItemView())
        assertFalse(neverJson.contains("\"end\""), neverJson)
        assertTrue(
            DefernoJson.encodeToString(ItemView.serializer(), habitWith(countBound).toItemView())
                .contains("""{"type":"after_count","n":10}"""),
        )
    }

    @Test
    fun theCycleMultiplierGoesBackOutUnderTheKeyItsCadenceUses() {
        // The domain condenses `n` and `interval` into one field, so the export has to pick the right
        // wire key back. Getting this wrong would produce a body the backend's Cadence rejects.
        val everyN = habitWith(Recurrence(RecurrenceFrequency.EveryNDays, interval = 30)).toItemView()
        assertEquals(30, everyN.recurrence?.n)
        assertNull(everyN.recurrence?.interval)

        val monthly = habitWith(Recurrence(RecurrenceFrequency.Monthly, interval = 2)).toItemView()
        assertEquals(2, monthly.recurrence?.interval)
        assertNull(monthly.recurrence?.n)

        val yearly = habitWith(
            Recurrence(RecurrenceFrequency.Yearly, interval = 3, month = 6, day = 14),
        ).toItemView()
        assertEquals(3, yearly.recurrence?.interval)
        assertNull(yearly.recurrence?.n)
    }

    @Test
    fun anUnknownCadenceIsExportedUnderThePreservedRawTokenAndRoundTripsAsItself() {
        // THE fix for the unrestorable backup. `Unknown` used to export `type = null`; now it re-emits
        // the token the read mapper preserved, so the item restores under its original cadence even
        // though this client version cannot model it.
        val future = Recurrence(RecurrenceFrequency.Unknown, rawType = "fortnightly", interval = 2)

        val exported = habitWith(future).toItemView()
        assertEquals("fortnightly", exported.recurrence?.type)

        val back = roundTrip(future)
        assertEquals(RecurrenceFrequency.Unknown, back?.frequency)
        assertEquals("fortnightly", back?.rawType)
    }

    @Test
    fun anExportedRecurrenceAlwaysCarriesATypeSoTheRestoreCanNeverBeRejected() {
        // The regression for the exact defect: `{"days":[]}` with no `type` key. The backend's Cadence is
        // internally tagged, so a tagless body is a hard 4xx — the backup would not restore at all.
        val rules = listOf(
            Recurrence(RecurrenceFrequency.Daily),
            Recurrence(RecurrenceFrequency.EveryNDays, interval = 3),
            Recurrence(RecurrenceFrequency.Custom, rrule = "FREQ=DAILY"),
            Recurrence(RecurrenceFrequency.Unknown, rawType = "fortnightly"),
        )

        for (rule in rules) {
            val exported = habitWith(rule).toItemView()
            val type = exported.recurrence?.type
            assertTrue(type != null && type.isNotEmpty(), "exported $rule with no wire type")
        }
    }

    @Test
    fun aCadenceWithNoNameableTokenIsSkippedRatherThanExportedTagless() {
        // The residual case: Unknown that could not even preserve a token (a rule object with no `type`
        // on the wire — a shape the server never emits). Skipping is the deliberate choice: omitting the
        // key restores through the import placeholder below, whereas exporting `{}` would fail the
        // restore outright. Chore takes the same path as Habit.
        val nameless = Recurrence(RecurrenceFrequency.Unknown, rawType = null, days = listOf("Mon"))

        assertNull(habitWith(nameless).toItemView().recurrence)
        val chore = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Active,
            recurrence = nameless,
            dateCreated = created,
        )
        assertNull(chore.toItemView().recurrence)
    }

    @Test
    fun anAbsentCadenceRestoresAsANamedPlaceholderRatherThanATaglessBody() {
        // The import half. `POST /habits` and `POST /chores` REQUIRE a recurrence, so an absent one needs
        // a fallback — and the old fallback (`RecurrenceDto()`) serialized to a body with no `type` at
        // all, which the backend rejects. `daily` is an explicit, always-valid placeholder: the item comes
        // back and can be corrected, instead of failing to restore.
        val habitPayload = ItemView.Habit(
            id = "h-1",
            orgSlug = "u-e4h2qk",
            title = "stretch",
            dateCreated = created.toString(),
            recurrence = null,
        ).toCreatePayload()
        assertEquals("daily", habitPayload.recurrence.type)
        assertTrue(DefernoJson.encodeToString(habitPayload).contains("\"type\":\"daily\""))

        val chorePayload = ItemView.Chore(
            id = "c-1",
            orgSlug = "u-e4h2qk",
            title = "trash",
            dateCreated = created.toString(),
            recurrence = null,
        ).toCreatePayload()
        assertEquals("daily", chorePayload.recurrence.type)

        // A cadence that IS present is passed through untouched — the placeholder never overrides it.
        val kept = ItemView.Habit(
            id = "h-2",
            orgSlug = "u-e4h2qk",
            title = "stretch",
            dateCreated = created.toString(),
            recurrence = habitWith(Recurrence(RecurrenceFrequency.EveryNDays, interval = 3))
                .toItemView().recurrence,
        ).toCreatePayload()
        assertEquals("every_n_days", kept.recurrence.type)
        assertEquals(3, kept.recurrence.n)
    }

    @Test
    fun aNonRecurringItemStillExportsNoRuleAtAll() {
        // The null-domain → null-wire case must stay distinct from "a rule we could not name".
        assertNull(habitWith(null).toItemView().recurrence)
    }
}

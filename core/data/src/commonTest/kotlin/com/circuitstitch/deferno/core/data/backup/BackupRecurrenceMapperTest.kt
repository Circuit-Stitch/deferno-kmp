package com.circuitstitch.deferno.core.data.backup

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.mapper.asChoreOrNull
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
 * collapsed to an unnamed "unknown" on read, a backup of such an item was not merely lossy, it was
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
            Recurrence(Cadence.Daily),
            Recurrence(Cadence.EveryNDays(30)),
            Recurrence(Cadence.Weekly(listOf("Mon", "Wed"))),
            Recurrence(Cadence.Monthly(interval = 2, on = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri"))),
            Recurrence(Cadence.Monthly(interval = 1, on = MonthlyAnchor.DayOfMonth(15))),
            Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14)),
            Recurrence(Cadence.Custom("FREQ=WEEKLY;BYDAY=MO,WE")),
        )

        for (rule in rules) assertEquals(rule, roundTrip(rule), "round-trip of $rule")
    }

    @Test
    fun allThreeEndBoundsSurviveTheRoundTripAndNeverEmitsNoEndKey() {
        val base = Recurrence(Cadence.EveryNDays(3))

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
    fun eachCadenceGoesBackOutUnderItsOwnNumericWireKeyAndNoOther() {
        // `n` (every_n_days) and `interval` (monthly/yearly) are separate wire keys that can never
        // co-occur, so each cadence must emit exactly one of them. Emitting the wrong one — or both —
        // would produce a body the backend's Cadence rejects.
        val everyN = habitWith(Recurrence(Cadence.EveryNDays(30))).toItemView()
        assertEquals(30, everyN.recurrence?.n)
        assertNull(everyN.recurrence?.interval)

        val monthly = habitWith(Recurrence(Cadence.Monthly(interval = 2))).toItemView()
        assertEquals(2, monthly.recurrence?.interval)
        assertNull(monthly.recurrence?.n)

        val yearly = habitWith(Recurrence(Cadence.Yearly(interval = 3, month = 6, day = 14))).toItemView()
        assertEquals(3, yearly.recurrence?.interval)
        assertNull(yearly.recurrence?.n)

        // And a cadence that owns neither key emits neither.
        val daily = habitWith(Recurrence(Cadence.Daily)).toItemView()
        assertNull(daily.recurrence?.n)
        assertNull(daily.recurrence?.interval)
    }

    @Test
    fun anUnmodelledCadenceIsExportedUnderThePreservedRawTokenAndRoundTripsAsItself() {
        // THE fix for the unrestorable backup. The unmodelled arm used to export `type = null`; now it
        // re-emits the token the read mapper preserved, so the item restores under its original cadence
        // even though this client version cannot model it.
        val future = Recurrence(Cadence.Unmodelled("fortnightly"))

        val exported = habitWith(future).toItemView()
        assertEquals("fortnightly", exported.recurrence?.type)
        assertEquals(future, roundTrip(future))
    }

    /**
     * The same #382 preservation rule, applied to a Chore's `cadence_mode` (#401). The mode is not
     * decoration — [CadenceMode.Rolling] is what makes the backend measure the next deadline from the
     * completion rather than from the rule — so a restore that silently rewrites it reschedules the
     * user's chore. Three separate ways that could happen, all pinned here: emitting the Kotlin variant
     * name instead of the wire token, flattening an unrecognised mode down to `rolling`, and dropping
     * the key so the restored chore falls back to the server's default.
     */
    @Test
    fun aChoresCadenceModeSurvivesTheExportImportRoundTripUnderItsOwnWireToken() {
        fun choreWith(mode: CadenceMode) = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Daily),
            cadenceMode = mode,
            dateCreated = created,
        )

        fun roundTripMode(mode: CadenceMode): CadenceMode {
            val json = DefernoJson.encodeToString(ItemView.serializer(), choreWith(mode).toItemView())
            val reread = DefernoJson.decodeFromString(ItemView.serializer(), json)
            return (reread as ItemView.Chore).asChoreOrNull()!!.cadenceMode
        }

        // The mode a future backend adds comes back as ITSELF, not as the default it is not.
        assertEquals(CadenceMode.Unmodelled("drifting"), roundTripMode(CadenceMode.Unmodelled("drifting")))
        assertEquals(CadenceMode.Fixed, roundTripMode(CadenceMode.Fixed))
        assertEquals(CadenceMode.Rolling, roundTripMode(CadenceMode.Rolling))

        // The exported bytes carry the WIRE token — `items.json` IS the API's own snake-case JSON
        // (ADR-0041), so `"Rolling"` / `"Unmodelled"` there would be a restore the server rejects or
        // misreads. Rolling emits its token explicitly, exactly as the server's own Serialize does.
        fun exportedJson(mode: CadenceMode) =
            DefernoJson.encodeToString(ItemView.serializer(), choreWith(mode).toItemView())

        val drifting = exportedJson(CadenceMode.Unmodelled("drifting"))
        assertTrue(drifting.contains("\"cadence_mode\":\"drifting\""), drifting)
        assertTrue(exportedJson(CadenceMode.Rolling).contains("\"cadence_mode\":\"rolling\""))
        assertTrue(exportedJson(CadenceMode.Fixed).contains("\"cadence_mode\":\"fixed\""))

        // …and the create payload the import replays carries the same token, so the row the server ends
        // up with agrees with the optimistic local row the importer upserts alongside it.
        val payload = choreWith(CadenceMode.Unmodelled("drifting")).toItemView().toCreatePayload()
        assertEquals("drifting", payload.cadenceMode)
    }

    @Test
    fun anExportedRecurrenceAlwaysCarriesATypeSoTheRestoreCanNeverBeRejected() {
        // The regression for the exact defect: `{"days":[]}` with no `type` key. The backend's Cadence is
        // internally tagged, so a tagless body is a hard 4xx — the backup would not restore at all.
        val rules = listOf(
            Recurrence(Cadence.Daily),
            Recurrence(Cadence.EveryNDays(3)),
            Recurrence(Cadence.Custom("FREQ=DAILY")),
            Recurrence(Cadence.Unmodelled("fortnightly")),
        )

        for (rule in rules) {
            val exported = habitWith(rule).toItemView()
            val type = exported.recurrence?.type
            assertTrue(type != null && type.isNotEmpty(), "exported $rule with no wire type")
        }
    }

    @Test
    fun aCadenceWithNoNameableTokenIsSkippedRatherThanExportedTagless() {
        // The residual case: an unmodelled cadence that could not even preserve a token (a rule object
        // with no `type` on the wire — a shape the server never emits, which the read mapper turns into
        // a BLANK token). Skipping is the deliberate choice: omitting the key restores through the
        // import placeholder below, whereas exporting `{}` would fail the restore outright. Chore takes
        // the same path as Habit.
        val nameless = Recurrence(Cadence.Unmodelled(""))

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
            recurrence = habitWith(Recurrence(Cadence.EveryNDays(3))).toItemView().recurrence,
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

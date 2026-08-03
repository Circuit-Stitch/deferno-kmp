package com.circuitstitch.deferno.core.network.dto

import com.circuitstitch.deferno.core.network.DefernoJson
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The wire contract for `recurrence` (#382) — asserted against the **Rust source**, not against
 * `contracts/openapi-0.1.json`, whose `Recurrence` schema is wrong (see CONTRACT-NOTES → "Recurrence").
 *
 * The backend hand-writes `Serialize`/`Deserialize` for `Recurrence` because `#[serde(flatten)]` does
 * not round-trip over an internally-tagged enum, so the shape is **flat**: the cadence's own fields sit
 * beside `type` at the top level and only the bound is nested, under `end`. These decode one literal
 * body per `Cadence` variant, prove the two tolerance invariants that keep the `/items` snapshot
 * decodable, and pin the encode side the create / convert / Backup bodies depend on.
 */
class RecurrenceDtoTest {

    private fun decode(json: String) = DefernoJson.decodeFromString(RecurrenceDto.serializer(), json)

    @Test
    fun everyCadenceDecodesFromItsFlatWireBody() {
        assertEquals(RecurrenceDto(type = "daily"), decode("""{"type":"daily"}"""))
        assertEquals(RecurrenceDto(type = "every_n_days", n = 3), decode("""{"type":"every_n_days","n":3}"""))
        assertEquals(
            RecurrenceDto(type = "weekly", days = listOf("Mon", "Wed")),
            decode("""{"type":"weekly","days":["Mon","Wed"]}"""),
        )
        assertEquals(
            RecurrenceDto(type = "monthly", interval = 1, on = MonthlyAnchorDto("day_of_month", day = 15)),
            decode("""{"type":"monthly","interval":1,"on":{"type":"day_of_month","day":15}}"""),
        )
        assertEquals(
            RecurrenceDto(
                type = "monthly",
                interval = 2,
                on = MonthlyAnchorDto("nth_weekday", nth = -1, weekday = "Fri"),
            ),
            decode("""{"type":"monthly","interval":2,"on":{"type":"nth_weekday","nth":-1,"weekday":"Fri"}}"""),
        )
        assertEquals(
            RecurrenceDto(type = "yearly", interval = 1, month = 6, day = 14),
            decode("""{"type":"yearly","interval":1,"month":6,"day":14}"""),
        )
        assertEquals(
            RecurrenceDto(type = "custom", rrule = "FREQ=WEEKLY;BYDAY=MO,WE"),
            decode("""{"type":"custom","rrule":"FREQ=WEEKLY;BYDAY=MO,WE"}"""),
        )
    }

    @Test
    fun theCadenceIsFlatBesideTypeAndOnlyTheBoundIsNested() {
        // The one structural claim everything else rests on. utoipa derived the published schema from the
        // Rust struct's FIELDS and never saw the hand-written impl, so it advertises
        // {"cadence":{…},"end":{…}} with `end` required — a shape the server emits for nothing. If this
        // assertion ever fails, re-read `backend/src/models/recurrence.rs`, not the OpenAPI file.
        val rule = decode(
            """{"type":"monthly","interval":2,"on":{"type":"nth_weekday","nth":-1,"weekday":"Fri"},"end":{"type":"on_date","date":"2027-01-31"}}""",
        )

        assertEquals("monthly", rule.type)
        assertEquals(2, rule.interval)
        assertEquals(-1, rule.on?.nth)
        assertEquals("on_date", rule.end?.type)
        assertEquals("2027-01-31", rule.end?.date)
    }

    @Test
    fun anAbsentEndDecodesToNullBecauseThatIsHowTheServerSpellsNever() {
        // `Recurrence::serialize` skips the `end` key entirely when the bound is Never, so absent is the
        // ONLY on-the-wire encoding of it. The DTO must therefore allow it to be absent (the published
        // schema marks `end` REQUIRED, which would have made this a decode failure).
        assertNull(decode("""{"type":"daily"}""").end)
        // The backend's Deserialize does accept the explicit form, so tolerate it.
        assertEquals("never", decode("""{"type":"daily","end":{"type":"never"}}""").end?.type)
    }

    @Test
    fun anUnknownCadenceTokenDecodesInsteadOfThrowing() {
        // THE invariant that keeps this a flat data class rather than a kotlinx sealed hierarchy.
        // `DefernoJson` registers no `polymorphicDefaultDeserializer`, so a sealed `RecurrenceDto` would
        // THROW on a future seventh cadence — inside the very same single-call `/items` decode whose
        // failure mode is the #381 cold-sync stall. A flat DTO cannot fail this way; the unmodelled
        // fields simply land nowhere and `type` is carried through for the mapper to degrade.
        val future = decode("""{"type":"fortnightly","every":2,"unit":"week"}""")

        assertEquals("fortnightly", future.type)
        assertNull(future.n)
        assertEquals(emptyList(), future.days)
    }

    @Test
    fun anUnknownAnchorOrBoundTokenAlsoDecodesInsteadOfThrowing() {
        // Same invariant one level down — the two NESTED objects are flat + tolerant for the same reason.
        val rule = decode(
            """{"type":"monthly","on":{"type":"last_business_day"},"end":{"type":"until_cancelled"}}""",
        )

        assertEquals("last_business_day", rule.on?.type)
        assertNull(rule.on?.day)
        assertEquals("until_cancelled", rule.end?.type)
    }

    @Test
    fun encodingEmitsTheFlatShapeAndOmitsEveryInapplicableField() {
        // The write side: create / convert bodies and the Backup file all serialize this DTO, and
        // `explicitNulls = false` means an inapplicable field is omitted rather than sent as null — which
        // matters because the server's `Cadence` is internally tagged and rejects a stray key.
        val daily = DefernoJson.encodeToString(RecurrenceDto(type = "daily"))
        assertEquals("""{"type":"daily"}""", daily)

        val monthly = DefernoJson.encodeToString(
            RecurrenceDto(
                type = "monthly",
                interval = 2,
                on = MonthlyAnchorDto("nth_weekday", nth = -1, weekday = "Fri"),
                end = RecurrenceEndDto(type = "on_date", date = "2027-01-31"),
            ),
        )
        assertTrue(monthly.contains("""{"type":"nth_weekday","nth":-1,"weekday":"Fri"}"""), monthly)
        assertTrue(monthly.contains(""""end":{"type":"on_date","date":"2027-01-31"}"""), monthly)
        assertFalse(monthly.contains("rrule"), "an inapplicable field is omitted, not sent as null")
        assertFalse(monthly.contains("\"n\""), "an inapplicable field is omitted, not sent as null")
    }

    @Test
    fun aTypeOnlyConstructionStaysValidSoEveryExistingCallSiteStillCompiles() {
        // The widening had to keep `RecurrenceDto()` and `RecurrenceDto("daily")` constructible: they are
        // used by the offline create path, the Backup import fallback and the capture command. A sealed
        // redesign would have broken all of them — a second reason the flat shape is the invariant.
        assertEquals(RecurrenceDto(type = "daily"), RecurrenceDto("daily"))
        assertNull(RecurrenceDto().type)
        assertEquals(emptyList(), RecurrenceDto().days)
    }
}

package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.SeriesOverride
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.EventDetailDto
import com.circuitstitch.deferno.core.network.dto.HabitDetailDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The wire `series` block → domain [com.circuitstitch.deferno.core.model.SeriesInputs] (#410), driven
 * through the **real** tolerant reader rather than by constructing DTOs, because half of what is under
 * test is what the *decoder* does to a block it dislikes.
 *
 * Two separable guarantees, and the tests are grouped by which one they defend:
 *
 * 1. **A malformed block never fails the enclosing decode.** `/items` is one call returning every row;
 *    a strict field anywhere inside it takes the entire cold sync down, which is #381 and it took the
 *    app with it. So the DTO is all-defaulted and every assertion below that feeds garbage still expects
 *    a complete, correctly-decoded list.
 * 2. **A block that cannot be read in full yields no inputs at all.** Salvaging the readable parts would
 *    produce a *wrong* grid rather than a smaller one — firings past a bound that was dropped, firings on
 *    days an unreadable EXDATE meant to remove. Under-reporting is a state the domain already has a word
 *    for; over-reporting shows the user appointments that do not exist.
 */
class SeriesInputsMapperTest {

    /** One `/items` row, with whatever `series` text the case under test needs spliced in. */
    private fun itemsJson(series: String?) = """
        [
          {"type":"chore","id":"c-1","org_slug":"u-1","title":"<title>",
           "date_created":"2026-05-04T01:53:05Z","series_id":"s-1",
           "recurrence":{"type":"weekly","days":["Tue"]}${series?.let { ",\"series\":$it" } ?: ""}},
          {"type":"task","id":"t-1","org_slug":"u-1","title":"<title>",
           "date_created":"2026-05-04T01:53:05Z"}
        ]
    """.trimIndent()

    private fun decode(series: String?): List<ItemView> =
        DefernoJson.decodeFromString(ListSerializer(ItemView.serializer()), itemsJson(series))

    /** The chore's mapped inputs, plus the assertion that the sibling row decoded regardless. */
    private fun seriesOf(json: String?) = decode(json).let { items ->
        assertEquals(2, items.size, "a series block must never cost the snapshot a row")
        items.firstNotNullOf { it.asChoreOrNull() }.series
    }

    private val fullBlock = """
        {"dtstart_local":"2026-08-04T23:59:59","tzid":"America/Los_Angeles",
         "until_utc":"2026-09-16T06:59:59Z","exdates":["2026-08-18T23:59:59"],
         "overrides":[
           {"recurrence_id":"2026-08-11T23:59:59","is_cancelled":true,"dtstart_local":null},
           {"recurrence_id":"2026-08-25T23:59:59","is_cancelled":false,"dtstart_local":"2026-08-26T18:30:00"}]}
    """.trimIndent()

    @Test
    fun aFullBlockMapsEveryFieldIncludingTheRenamedMovedTime() {
        val series = assertNotNull(seriesOf(fullBlock))

        assertEquals(LocalDateTime.parse("2026-08-04T23:59:59"), series.anchorLocal)
        // The raw IANA token, NOT a resolved TimeZone: a zone this build's tzdb cannot place has to
        // survive the mapper and surface as a refusal at expansion time, not throw here.
        assertEquals("America/Los_Angeles", series.tzid)
        // The only field in the block that is an instant rather than a wall time.
        assertEquals(Instant.parse("2026-09-16T06:59:59Z"), series.untilUtc)
        assertEquals(listOf(LocalDateTime.parse("2026-08-18T23:59:59")), series.exdates)
        assertEquals(
            listOf(
                SeriesOverride(LocalDateTime.parse("2026-08-11T23:59:59"), isCancelled = true),
                SeriesOverride(
                    recurrenceId = LocalDateTime.parse("2026-08-25T23:59:59"),
                    movedToLocal = LocalDateTime.parse("2026-08-26T18:30:00"),
                ),
            ),
            series.overrides,
        )
    }

    @Test
    fun theOverridesMovedTimeIsNeverConfusedWithTheAnchor() {
        val series = assertNotNull(seriesOf(fullBlock))

        // The wire spells both of these `dtstart_local`, one nesting level apart, meaning opposite
        // things. The anchor is frozen; the override's is precisely the movement. If a refactor ever
        // crosses them, this fails rather than quietly re-anchoring the series on a rescheduled day.
        assertEquals(LocalDateTime.parse("2026-08-04T23:59:59"), series.anchorLocal)
        assertEquals(LocalDateTime.parse("2026-08-26T18:30:00"), series.overrides[1].movedToLocal)
    }

    @Test
    fun anAbsentBlockIsNullAndAnEmptyOneIsNot() {
        // The distinction the nullable type exists for: absent = "no series row backs this item, this
        // device cannot reproduce that grid"; present-and-empty = "reproducible, and nothing is excluded".
        assertNull(seriesOf(null))

        val empty = assertNotNull(
            seriesOf("""{"dtstart_local":"2026-08-04T23:59:59","tzid":"UTC","until_utc":null,"exdates":[],"overrides":[]}"""),
        )
        assertEquals(emptyList(), empty.exdates)
        assertEquals(emptyList(), empty.overrides)
        assertNull(empty.untilUtc)
    }

    @Test
    fun anExplicitNullBlockIsTheAbsentCase() {
        // The create responses send `"series": null` rather than omitting the key, and `null` there
        // means the same thing an absent key does. Seeding a cache from a create echo would otherwise
        // look like a series with no inputs instead of an item whose block simply was not sent.
        assertNull(seriesOf("null"))
    }

    @Test
    fun aBlockWithNoAnchorYieldsNoInputsAndCostsTheSnapshotNothing() {
        // `complete_by` is NOT a fallback anchor — on a recurring definition it is a cursor that has
        // walked forward, so guessing with it would bake the walked position in as the frozen DTSTART.
        assertNull(seriesOf("""{"tzid":"America/Los_Angeles","exdates":[],"overrides":[]}"""))
        assertNull(seriesOf("""{"dtstart_local":"not-a-datetime","tzid":"UTC","exdates":[],"overrides":[]}"""))
    }

    @Test
    fun aBlockWithNoZoneYieldsNoInputs() {
        // The zone is the one the series was FROZEN in. Defaulting to the device's would silently
        // re-time every firing for anyone who has moved country — the exact thing `tzid` prevents.
        assertNull(seriesOf("""{"dtstart_local":"2026-08-04T23:59:59","exdates":[],"overrides":[]}"""))
        assertNull(seriesOf("""{"dtstart_local":"2026-08-04T23:59:59","tzid":"","exdates":[],"overrides":[]}"""))
    }

    @Test
    fun anUnreadableBoundRefusesTheWholeBlockRatherThanDroppingTheBound() {
        // Dropping it would extend the series past the end of its Segment — inventing firings. Refusing
        // the block shows none, which is recoverable and honest.
        assertNull(
            seriesOf("""{"dtstart_local":"2026-08-04T23:59:59","tzid":"UTC","until_utc":"soon","exdates":[],"overrides":[]}"""),
        )
    }

    @Test
    fun anUnreadableExclusionRefusesTheWholeBlockRatherThanDroppingTheExclusion() {
        // Same direction: a dropped EXDATE resurrects a firing the user deleted.
        assertNull(
            seriesOf("""{"dtstart_local":"2026-08-04T23:59:59","tzid":"UTC","exdates":["nope"],"overrides":[]}"""),
        )
    }

    @Test
    fun anUnreadableOverrideRefusesTheWholeBlockRatherThanDroppingTheOverride() {
        // And a dropped override puts a rescheduled firing back on the day it moved off.
        assertNull(
            seriesOf(
                """{"dtstart_local":"2026-08-04T23:59:59","tzid":"UTC","exdates":[],
                   "overrides":[{"recurrence_id":"whenever","is_cancelled":false}]}""",
            ),
        )
        assertNull(
            seriesOf(
                """{"dtstart_local":"2026-08-04T23:59:59","tzid":"UTC","exdates":[],
                   "overrides":[{"recurrence_id":"2026-08-11T23:59:59","dtstart_local":"whenever"}]}""",
            ),
        )
    }

    @Test
    fun theDetailReadCarriesTheBlockOnAllThreeKindsToo() {
        // Six DTO sites carry `series`, and the three snapshot variants above are only half of them.
        // The detail shapes are what a create response and every `GET /habits/{id}`-style read decode
        // through, so a mapper arm forgotten here would leave a freshly opened item unable to expand
        // the grid its own tree row could.
        fun detail(kind: String) = """
            {"id":"d-1","org_slug":"u-1","title":"<title>","date_created":"2026-05-04T01:53:05Z",
             "recurrence":{"type":"weekly","days":["Tue"]},"series_id":"s-1","series":$fullBlock
             ${if (kind == "event") ""","all_day":false""" else ""}}
        """.trimIndent()

        val anchor = LocalDateTime.parse("2026-08-04T23:59:59")
        assertEquals(
            anchor,
            DefernoJson.decodeFromString(HabitDetailDto.serializer(), detail("habit")).toDomain().series?.anchorLocal,
        )
        assertEquals(
            anchor,
            DefernoJson.decodeFromString(ChoreDetailDto.serializer(), detail("chore")).toDomain().series?.anchorLocal,
        )
        val event = DefernoJson.decodeFromString(EventDetailDto.serializer(), detail("event")).toDomain()
        assertEquals(anchor, event.series?.anchorLocal)
        assertEquals(2, event.series?.overrides?.size)
    }

    @Test
    fun unknownKeysInsideTheBlockAreIgnoredNotFatal() {
        // The block is additive and the backend will grow it (the `SegmentView` chain eras are already
        // in the spec). A new key must degrade to "ignored", never to a failed snapshot.
        val series = assertNotNull(
            seriesOf(
                """{"dtstart_local":"2026-08-04T23:59:59","tzid":"UTC","exdates":[],"overrides":[],
                   "some_future_field":{"nested":true}}""",
            ),
        )
        assertEquals("UTC", series.tzid)
    }
}

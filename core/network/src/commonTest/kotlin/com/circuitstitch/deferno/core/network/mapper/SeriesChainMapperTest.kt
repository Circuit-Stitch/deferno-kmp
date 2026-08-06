package com.circuitstitch.deferno.core.network.mapper

import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.SeriesChain
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.ItemView
import kotlinx.datetime.LocalDateTime
import kotlinx.serialization.builtins.ListSerializer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * The wire `series_chain` block → domain [SeriesChain] (#383, ADR-0053 decision 3), driven through the
 * **real** tolerant reader rather than by constructing DTOs, because half of what is under test is what
 * the *decoder* does with a block it dislikes — and because an unmodelled key would sail straight past a
 * hand-built DTO while being invisible on the wire, which is how #381 and #382 both shipped.
 *
 * Three separable guarantees, and the tests are grouped by which one they defend:
 *
 * 1. **A malformed chain never fails the enclosing decode.** #383 *is* "a Habit/Chore/Event cannot be
 *    opened"; a strict field inside this block would be a second, subtler way to reproduce it.
 * 2. **One unreadable era never costs the others.** The chain is a list of independent records, so it
 *    drops the era it cannot use and flips `truncated` — announced, never silent.
 * 3. **Nothing is invented to fill a gap.** A missing id or a garbled tombstone is refused rather than
 *    guessed, because a deleted era read as live gets expanded, resurrecting dates the user removed.
 */
class SeriesChainMapperTest {

    /** One `GET /items/{id}` body — the detail read, which is the only place a chain rides. */
    private fun detailJson(chain: String?) = """
        {"type":"chore","id":"seg-2","org_slug":"u-1","title":"<title>",
         "date_created":"2026-05-04T01:53:05Z","series_id":"s-2",
         "recurrence":{"type":"weekly","days":["Tue"]},
         "origin_label":"acme/repo#7"${chain?.let { ",\"series_chain\":$it" } ?: ""}}
    """.trimIndent()

    private fun decode(chain: String?): ItemView.Chore =
        DefernoJson.decodeFromString(ItemView.serializer(), detailJson(chain)) as ItemView.Chore

    private fun chainOf(chain: String?): SeriesChain? = decode(chain).seriesChain.toDomain()

    /**
     * Two eras: a superseded `weekly Mon` closed by an exclusive [[Segment]] bound, and the live
     * `weekly Tue` that replaced it. The shape every other case is a mutation of.
     */
    private val fullChain = """
        {"head":"seg-2","requested":"seg-1","truncated":false,
         "segments":[
           {"id":"seg-1","deleted_at":null,
            "recurrence":{"type":"weekly","days":["Mon"]},
            "series":{"dtstart_local":"2026-01-05T09:00:00","tzid":"America/Los_Angeles",
                      "until_utc":"2026-06-02T16:00:00Z","exdates":[],"overrides":[]}},
           {"id":"seg-2","deleted_at":null,
            "recurrence":{"type":"weekly","days":["Tue"]},
            "series":{"dtstart_local":"2026-06-02T09:00:00","tzid":"America/Los_Angeles",
                      "until_utc":null,"exdates":[],"overrides":[]}}]}
    """.trimIndent()

    @Test
    fun aFullChainMapsEveryEraRootToHead() {
        val chain = assertNotNull(chainOf(fullChain))

        assertEquals("seg-2", chain.head)
        // The caller held the ROOT id and still landed on the item — the stale deep link that resolves.
        assertEquals("seg-1", chain.requested)
        assertFalse(chain.truncated)
        assertEquals(listOf("seg-1", "seg-2"), chain.segments.map { it.id })

        // Each era carries its OWN rule and its OWN inputs; neither is the item's current one.
        assertEquals(Cadence.Weekly(listOf("Mon")), chain.segments[0].recurrence?.cadence)
        assertEquals(Cadence.Weekly(listOf("Tue")), chain.segments[1].recurrence?.cadence)
        assertEquals(LocalDateTime.parse("2026-01-05T09:00:00"), chain.segments[0].series?.anchorLocal)
        assertEquals(LocalDateTime.parse("2026-06-02T09:00:00"), chain.segments[1].series?.anchorLocal)
        assertEquals("America/Los_Angeles", chain.segments[0].series?.tzid)
    }

    @Test
    fun onlyTheSeriesBoundStopsASupersededEraNotItsRule() {
        val chain = assertNotNull(chainOf(fullChain))

        // The rule of a superseded era still reads OPEN-ENDED — the backend does not truncate it. What
        // stops it is `series.until_utc`, an EXCLUSIVE bound. If a refactor ever drops that field while
        // keeping the rule, this era expands straight through the change and mints dates the *next* era
        // already owns, so both halves are pinned together here rather than a page apart.
        assertEquals(RecurrenceBound.Never, chain.segments[0].recurrence?.bound)
        assertEquals(Instant.parse("2026-06-02T16:00:00Z"), chain.segments[0].series?.untilUtc)
        // …and the live era has no bound at all, which is what makes it the live one.
        assertNull(chain.segments[1].series?.untilUtc)
    }

    @Test
    fun aTruncatedChainSurvivesAsPartialRatherThanAsAFailure() {
        // A cycle, a dangling pointer or the depth cap server-side. The honest flag exists precisely so
        // the detail still renders; treating it as an error would reintroduce #383 for these items.
        val chain = assertNotNull(chainOf(fullChain.replace("\"truncated\":false", "\"truncated\":true")))

        assertTrue(chain.truncated)
        assertEquals(2, chain.segments.size, "truncated means partial, not empty")
    }

    @Test
    fun aTombstonedEraKeepsItsIdAndSaysSo() {
        // A deleted era stays listed so the chain of records does not break, but it contributes linkage
        // ONLY: expanding it would resurrect dates the user deliberately removed. `isTombstoned` is the
        // whole signal a consumer has for that, so the timestamp must survive the mapper intact.
        val chain = assertNotNull(
            chainOf(fullChain.replace("""{"id":"seg-1","deleted_at":null""", """{"id":"seg-1","deleted_at":"2026-06-02T16:00:00Z"""")),
        )

        assertEquals(Instant.parse("2026-06-02T16:00:00Z"), chain.segments[0].deletedAt)
        assertTrue(chain.segments[0].isTombstoned)
        assertFalse(chain.segments[1].isTombstoned)
        assertFalse(chain.truncated, "a tombstoned era is a normal era, not a dropped one")
    }

    @Test
    fun anAbsentChainIsNullAndSoIsAnExplicitNull() {
        // Absent is the normal case: the block is present ONLY once a rule has been changed at least
        // once, so every unmodified recurring item — and every Task — reads `null` here forever.
        assertNull(chainOf(null))
        // `explicitNulls = false` means the server may send either form; they mean the same thing.
        assertNull(chainOf("null"))
    }

    @Test
    fun aOneOffEventEraCarriesNoRuleAndThatIsNotAnError() {
        // `recurrence: null` is the one-off Event — the only repeating-item shape with no rule. Refusing
        // the era for the missing field would hide the very shape the chain exists to explain.
        val chain = assertNotNull(
            chainOf(
                """{"head":"seg-2","requested":"seg-2","truncated":false,
                    "segments":[
                      {"id":"seg-1","recurrence":null,"series":null,"deleted_at":null},
                      {"id":"seg-2","recurrence":{"type":"daily"},"series":null,"deleted_at":null}]}""",
            ),
        )

        assertEquals(2, chain.segments.size)
        assertNull(chain.segments[0].recurrence)
        assertEquals(Cadence.Daily, chain.segments[1].recurrence?.cadence)
        assertFalse(chain.truncated)
    }

    @Test
    fun anUnreadableSeriesCostsAnEraItsInputsNotItsPlaceInTheChain() {
        // Inherited from SeriesInputsMapper: a block that cannot be read in full yields NO inputs, since
        // a half-read grid is a wrong grid rather than a smaller one. The era itself is still real — it
        // renders as history this device cannot expand, which is exactly what a wire `series: null`
        // already means, so the two are correctly indistinguishable.
        // `fullChain` with the ROOT era's `tzid` removed — the zone is the one the series was FROZEN
        // in, so defaulting to the device's would silently re-time every firing of that era.
        val chain = assertNotNull(
            chainOf(
                """{"head":"seg-2","requested":"seg-1","truncated":false,
                    "segments":[
                      {"id":"seg-1","deleted_at":null,
                       "recurrence":{"type":"weekly","days":["Mon"]},
                       "series":{"dtstart_local":"2026-01-05T09:00:00",
                                 "until_utc":"2026-06-02T16:00:00Z","exdates":[],"overrides":[]}},
                      {"id":"seg-2","deleted_at":null,
                       "recurrence":{"type":"weekly","days":["Tue"]},
                       "series":{"dtstart_local":"2026-06-02T09:00:00","tzid":"America/Los_Angeles",
                                 "until_utc":null,"exdates":[],"overrides":[]}}]}""",
            ),
        )

        assertEquals(2, chain.segments.size)
        assertNull(chain.segments[0].series, "a zone-less block yields no inputs")
        assertEquals(Cadence.Weekly(listOf("Mon")), chain.segments[0].recurrence?.cadence)
        assertNotNull(chain.segments[1].series, "the sibling era is untouched")
        assertFalse(chain.truncated, "refusing INPUTS is not dropping an ERA")
    }

    @Test
    fun anEraWithNoIdIsDroppedAndTheChainSaysSoRatherThanDying() {
        // The id IS the era — without one there is nothing to address and no way to tell two apart. But
        // one unusable era must not cost the user the rest of the history, so it is dropped and
        // `truncated` announces the gap. Silent partial history is the failure mode being avoided.
        val chain = assertNotNull(chainOf(fullChain.replace("\"id\":\"seg-1\"", "\"id\":\"  \"")))

        assertEquals(listOf("seg-2"), chain.segments.map { it.id })
        assertTrue(chain.truncated, "a dropped era must never be silent")
    }

    @Test
    fun anEraWithAGarbledTombstoneIsDroppedRatherThanReadAsLive() {
        // The one direction that is NOT recoverable. Mapping an unparseable `deleted_at` to `null` would
        // present a deleted era as live, and a live era gets expanded (#395) — resurrecting dates the
        // user deliberately removed. Inventing an Instant to mark it tombstoned would fabricate a fact.
        val chain = assertNotNull(
            chainOf(fullChain.replace("""{"id":"seg-1","deleted_at":null""", """{"id":"seg-1","deleted_at":"whenever"""")),
        )

        assertEquals(listOf("seg-2"), chain.segments.map { it.id })
        assertTrue(chain.truncated)
    }

    @Test
    fun aHeadlessChainIsRefusedWholeAndTheItemStillOpens() {
        // The Head is the id every write and every other read addresses this item by, and the first
        // segment is the ROOT era — the opposite end — so it cannot stand in. Refusing costs the era
        // history; it must not cost the item, which is the whole of #383.
        assertNull(chainOf(fullChain.replace("\"head\":\"seg-2\",", "")))
        assertNull(chainOf(fullChain.replace("\"head\":\"seg-2\"", "\"head\":\"\"")))
        // The item around it decoded regardless — including its own top-level fields.
        assertEquals("seg-2", decode(fullChain.replace("\"head\":\"seg-2\",", "")).id)
    }

    @Test
    fun anAbsentRequestedReadsAsTheHeadRatherThanRefusingTheChain() {
        // #395 uses the head/requested pair to say "you followed an older link". Defaulting to the head
        // can only suppress that note — under-reporting, the recoverable direction — whereas refusing
        // the block would throw away every era over a field that decides one line of copy.
        val chain = assertNotNull(chainOf(fullChain.replace("\"requested\":\"seg-1\",", "")))

        assertEquals("seg-2", chain.head)
        assertEquals("seg-2", chain.requested)
        assertEquals(2, chain.segments.size)
    }

    @Test
    fun aChainMissingEveryRequiredFieldNeverCostsTheEnclosingDecodeARow() {
        // The spec marks all four of `head`/`requested`/`segments`/`truncated` REQUIRED, and every one
        // is defaulted anyway. This is why: a strict field here would fail the enclosing decode, and the
        // enclosing decode is sometimes `/items` — one call returning every row, where a throw is the
        // whole-snapshot cold-sync stall of #381. `/items` never sends a chain today; a server that
        // started to must not be able to take the snapshot down, so both rows still arrive complete.
        val json = """
            [
              {"type":"chore","id":"c-1","org_slug":"u-1","title":"<title>",
               "date_created":"2026-05-04T01:53:05Z",
               "series_chain":{"segments":[{"deleted_at":"whenever"}],"unheard_of":true}},
              {"type":"task","id":"t-1","org_slug":"u-1","title":"<title>",
               "date_created":"2026-05-04T01:53:05Z"}
            ]
        """.trimIndent()

        val items = DefernoJson.decodeFromString(ListSerializer(ItemView.serializer()), json)

        assertEquals(2, items.size, "a chain must never cost the snapshot a row")
        // Decoded fine, then refused by the mapper — the two-layer split this file is about.
        assertNull(items.firstNotNullOf { it as? ItemView.Chore }.seriesChain.toDomain())
    }

    @Test
    fun everyItemVariantDeclaresTheDetailOnlyFields() {
        // `ItemDetail` appends `series_chain` + `origin_label` to the envelope regardless of kind, and an
        // UNMODELLED wire field is invisible to the contract-fixture harness — the exact failure mode
        // that shipped #381 and #382. One forgotten variant would silently swallow the chain for that
        // kind alone, which on Habit/Chore/Event is the whole of #383's payload.
        fun body(kind: String) = """
            {"type":"$kind","id":"i-1","org_slug":"u-1","title":"<title>",
             "date_created":"2026-05-04T01:53:05Z","origin_label":"acme/repo#7",
             "series_chain":$fullChain}
        """.trimIndent()

        listOf("task", "habit", "chore", "event").forEach { kind ->
            val item = DefernoJson.decodeFromString(ItemView.serializer(), body(kind))
            val (chain, label) = when (item) {
                is ItemView.Task -> item.seriesChain to item.originLabel
                is ItemView.Habit -> item.seriesChain to item.originLabel
                is ItemView.Chore -> item.seriesChain to item.originLabel
                is ItemView.Event -> item.seriesChain to item.originLabel
            }
            assertEquals("acme/repo#7", label, "$kind dropped origin_label")
            assertEquals("seg-2", assertNotNull(chain.toDomain(), "$kind dropped series_chain").head)
        }
    }

    @Test
    fun unknownKeysInsideTheChainAreIgnoredNotFatal() {
        // The block is additive and ADR-0053 already widened it once (bare ids → per-era objects). The
        // next widening must degrade to "ignored", never to an item that will not open.
        val chain = assertNotNull(
            chainOf(
                """{"head":"seg-2","requested":"seg-2","truncated":false,"depth_cap":64,
                    "segments":[{"id":"seg-1","recurrence":{"type":"daily"},"series":null,
                                 "deleted_at":null,"some_future_field":{"nested":true}}]}""",
            ),
        )

        assertEquals(listOf("seg-1"), chain.segments.map { it.id })
        assertFalse(chain.truncated)
    }
}

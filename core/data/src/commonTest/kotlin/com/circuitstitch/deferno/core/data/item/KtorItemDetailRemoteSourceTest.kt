package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemRef
import com.circuitstitch.deferno.core.model.OccurrenceResolution
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorItemDetailRemoteSource] (#383) over Ktor's MockEngine on the JVM-fast path
 * (ADR-0006) — the sibling of `KtorItemSnapshotSourceTest`, and the only place the client's **first**
 * `GET /items/{id}` call is exercised end to end: the URL it builds, the envelope unwrap, the
 * polymorphic decode, and the condensation into [ItemDetailRead].
 *
 * The bodies are trimmed from the captured `contracts/fixtures/item-detail-*.json` envelopes (the full
 * shapes are asserted field-by-field by the golden-envelope harness in `core:network`); what is pinned
 * *here* is the seam that harness cannot see — that the decoded row is condensed to the **concrete**
 * record the local stores hold, and that `today_occurrence`'s two "no fact" arms stay distinguishable.
 */
class KtorItemDetailRemoteSourceTest {

    private val ref = ItemRef("2975f1a1-7657-4b2e-82c6-8e98f8bf5fa8", ItemKind.Chore)

    @Test
    fun fetchAddressesTheKindNeutralItemRouteByRawId() = runTest {
        // Not `/chores/{id}` — the shipped contract has no `get` on any per-kind route, only `delete`
        // and `patch`. The path segment is the raw UUID, never a kind-typed id.
        var requested: String? = null
        val source = KtorItemDetailRemoteSource(
            client {
                requested = it.url.encodedPath
                respondJson(choreBody)
            },
        )

        source.fetch(ref)

        assertEquals("/api/items/2975f1a1-7657-4b2e-82c6-8e98f8bf5fa8", requested)
    }

    @Test
    fun fetchCondensesARecurringRowToItsConcreteRecord() = runTest {
        val source = KtorItemDetailRemoteSource(client { respondJson(choreBody) })

        val read = (source.fetch(ref) as RemoteSnapshot.Available).value

        // The CONCRETE row, not the lossy read projection: the repository upserts this straight into
        // the per-kind store, including for a definition this device has never cached, so every column
        // the store needs has to survive the seam.
        val chore = assertNotNull(read.chore)
        assertEquals("2975f1a1-7657-4b2e-82c6-8e98f8bf5fa8", chore.id.value)
        assertEquals("u-e4h2qk", chore.orgSlug, "the projection carries no org_slug — the record must")
        assertNotNull(chore.dateCreated, "nor a date_created")
        assertEquals(listOf("medical"), chore.labels)
        assertNull(read.habit)
        assertNull(read.event)
        assertNull(read.task)
    }

    @Test
    fun fetchRecordsThePlaceholderAsAnsweredButNotAsAFact() = runTest {
        // The all-zeroes id is the backend's "nothing recorded for this date" sentinel, sent INSTEAD of
        // omitting the field. Both halves matter and they say different things: the field's presence is
        // what makes the day covered (the server answered), the zero id is what withholds the fact
        // (nothing was recorded). Collapse them and every never-yet-resolved day reads Unknown forever
        // — or worse, `status: scheduled`, which is a *reading*, gets manufactured into the fact table.
        val source = KtorItemDetailRemoteSource(client { respondJson(choreBody) })

        val read = (source.fetch(ref) as RemoteSnapshot.Available).value

        assertNull(read.todayFact, "a placeholder is not a stored resolution")
        // And the day it answered for is the SERVER's, taken off the same wire field the fact is keyed
        // on — never the caller's `today`, which the request never sent and the server never saw.
        assertEquals(LocalDate(2026, 8, 5), read.answeredForDate, "but the server did answer for the day")
    }

    @Test
    fun fetchLandsARealOccurrenceAsAFactKeyedOnTheCallersKind() = runTest {
        val eventRef = ItemRef("1489006f-78d6-4262-8de9-c2e049a3dbaf", ItemKind.Event)
        val source = KtorItemDetailRemoteSource(client { respondJson(eventBody) })

        val read = (source.fetch(eventRef) as RemoteSnapshot.Available).value

        val fact = assertNotNull(read.todayFact)
        assertEquals(ItemKind.Event, fact.kind)
        assertEquals("1489006f-78d6-4262-8de9-c2e049a3dbaf", fact.definitionId)
        assertEquals(LocalDate(2026, 8, 5), fact.date)
        // The wire's Event vocabulary spells the called-off outcome `dropped`; the domain keeps one
        // name for it across both kind vocabularies (`Skipped`).
        assertEquals(OccurrenceResolution.Skipped, fact.resolution)
        assertEquals(LocalDate(2026, 8, 5), read.answeredForDate)
    }

    @Test
    fun fetchDecodesTheSeriesChainWithoutItReachingAnyRecord() = runTest {
        val eventRef = ItemRef("1489006f-78d6-4262-8de9-c2e049a3dbaf", ItemKind.Event)
        val source = KtorItemDetailRemoteSource(client { respondJson(eventBody) })

        val read = (source.fetch(eventRef) as RemoteSnapshot.Available).value

        val chain = assertNotNull(read.chain)
        assertEquals("1489006f-78d6-4262-8de9-c2e049a3dbaf", chain.head)
        assertEquals(2, chain.segments.size)
        assertEquals(false, chain.truncated)
        // The superseded era's bound rides its own series block, not its rule — the rule still reads
        // open-ended, which is why expanding an era without applying `untilUtc` mints another era's dates.
        assertEquals("d4f26212-07ac-4ebc-b5d9-fe4649a69a3e", chain.segments[0].id)
        assertNotNull(chain.segments[0].series?.untilUtc)
        assertEquals(false, chain.segments[0].isTombstoned)
    }

    @Test
    fun fetchCarriesNoChainForAnItemWhoseRuleHasNeverChanged() = runTest {
        // Absent is the one-era statement, not an empty chain — the backend only sends the block once
        // `segments.len() >= 2`.
        val source = KtorItemDetailRemoteSource(client { respondJson(choreBody) })

        assertNull((source.fetch(ref) as RemoteSnapshot.Available).value.chain)
    }

    @Test
    fun fetchReportsUnavailableOnFailureSoTheCachedRowStillRenders() = runTest {
        // Offline-first (ADR-0001): a failed detail read must never take the cached definition down.
        val source = KtorItemDetailRemoteSource(client { respond("", HttpStatusCode.ServiceUnavailable) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetch(ref))
    }

    // --- bodies, trimmed from the captured contracts/fixtures/item-detail-*.json ---

    private val choreBody = """
        {"version":"0.1","data":{
          "type":"chore","kind":"chore","id":"2975f1a1-7657-4b2e-82c6-8e98f8bf5fa8",
          "org_slug":"u-e4h2qk","owner_org_id":"ebca93e5-d663-4624-9fe9-c5361b5b4390",
          "title":"<title>","description":"<description>","labels":["medical"],
          "status":"active","pinned":true,"priority":"normal",
          "date_created":"2026-05-11T14:54:53.106944473Z","updated_at":"2026-07-23T22:39:50.822471644Z",
          "recurrence":{"type":"every_n_days","n":30},"cadence_mode":"rolling",
          "series_id":"aa8ef113-e77c-4ed5-967d-e604aa3db72c","complete_by":"2026-08-23T06:59:59Z",
          "series":{"dtstart_local":"2026-08-22T23:59:59","tzid":"America/Los_Angeles",
                    "until_utc":null,"exdates":[],"overrides":[]},
          "today_occurrence":{"id":"00000000-0000-0000-0000-000000000000",
                              "parent_id":"2975f1a1-7657-4b2e-82c6-8e98f8bf5fa8",
                              "scheduled_date":"2026-08-05","complete_by":"2026-08-06T06:59:59Z",
                              "status":"scheduled","comment":[],"attachments":[]},
          "ref":"u-e4h2qk-275","sequence":275,"rev":1710,"blocked":false,"is_blocker":false
        }}
    """.trimIndent()

    private val eventBody = """
        {"version":"0.1","data":{
          "type":"event","kind":"event","id":"1489006f-78d6-4262-8de9-c2e049a3dbaf",
          "org_slug":"u-e4h2qk","owner_org_id":"ebca93e5-d663-4624-9fe9-c5361b5b4390",
          "title":"<title>","description":"<description>","labels":[],
          "status":"active","pinned":false,"priority":"normal","all_day":false,
          "date_created":"2026-05-02T15:00:34.693604023Z","updated_at":"2026-08-05T16:06:39.180420612Z",
          "recurrence":{"type":"weekly","days":["Sat"],"end":{"type":"on_date","date":"2026-06-12"}},
          "series_id":"e75a70de-b209-4eaf-9a3d-19fdfd4c258b","complete_by":null,
          "start_time_of_day":"09:00:00","end_time_of_day":"10:30:00","end_time":"2026-04-18T17:30:00Z",
          "series":{"dtstart_local":"2026-04-18T09:00:00","tzid":"America/Los_Angeles",
                    "until_utc":null,"exdates":[],"overrides":[]},
          "series_chain":{"head":"1489006f-78d6-4262-8de9-c2e049a3dbaf",
                          "requested":"1489006f-78d6-4262-8de9-c2e049a3dbaf","truncated":false,
                          "segments":[
                            {"id":"d4f26212-07ac-4ebc-b5d9-fe4649a69a3e","deleted_at":null,
                             "recurrence":{"type":"weekly","days":["Sat"]},
                             "series":{"dtstart_local":"2026-04-18T09:00:00","tzid":"America/Los_Angeles",
                                       "until_utc":"2026-04-18T16:00:00Z","exdates":[],"overrides":[]}},
                            {"id":"1489006f-78d6-4262-8de9-c2e049a3dbaf","deleted_at":null,
                             "recurrence":{"type":"weekly","days":["Sat"],
                                           "end":{"type":"on_date","date":"2026-06-12"}},
                             "series":{"dtstart_local":"2026-04-18T09:00:00","tzid":"America/Los_Angeles",
                                       "until_utc":null,"exdates":[],"overrides":[]}}]},
          "today_occurrence":{"id":"ca02ee6e-bea4-4ce6-a7ef-248806615370",
                              "parent_id":"1489006f-78d6-4262-8de9-c2e049a3dbaf",
                              "scheduled_date":"2026-08-05","complete_by":"2026-08-05T16:00:00Z",
                              "status":"dropped","comment":[],"attachments":[]},
          "ref":"u-e4h2qk-176","sequence":176,"rev":2047,"blocked":false,"is_blocker":false
        }}
    """.trimIndent()

    // --- test helpers (mirrors KtorItemSnapshotSourceTest) ---

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/api/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

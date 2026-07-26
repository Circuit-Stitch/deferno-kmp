package com.circuitstitch.deferno.core.network

import com.circuitstitch.deferno.core.network.dto.ActivityFeedDto
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Wire-shape coverage for `GET /activity` (#364), driven through the **shipping** read path — the
 * tolerant reader, envelope unwrap and version gate in [requestApi] over a Ktor `MockEngine` — exactly
 * as [ContractFixtureParseTest] does.
 *
 * ## Provenance, stated plainly
 *
 * These payloads are **hand-authored from the backend's `ActivityEntryDto` / `ActivityFeed` structs and
 * its envelope serializer**, NOT captured from a live backend, so they deliberately do **not** live in
 * `contracts/fixtures/` — that folder's contract is "real responses captured from staging", and quietly
 * adding a synthesised file would erode the one guarantee it exists to make. The shapes here are still
 * derived rather than invented: neither DTO carries a `skip_serializing_if`, so serde emits every field
 * including explicit nulls, which is what these payloads reproduce.
 *
 * A live capture is still owed — see the follow-up on #364. When it lands it belongs in
 * `contracts/fixtures/activity.json` with a handler wired into `ContractFixtureParseTest`, and this file
 * can shrink to the degradation cases below (which a single captured page will not exercise).
 */
class ActivityFeedParseTest {

    /** A sync-mode page: `next_since` populated, `next_before` null (each mode nulls the other's cursor). */
    private val syncPage = """
        {"version":"0.1","data":{
          "entries":[
            {"entry_id":"6f1b4d5e-1c2a-4f3b-9d8e-0a1b2c3d4e5f",
             "org_id":"11111111-2222-3333-4444-555555555555",
             "user_id":"99999999-8888-7777-6666-555555555555",
             "actor_kind":"human","provider":null,"source":"web",
             "item_id":"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
             "occurrence":null,"series_id":null,
             "action_kind":"updated",
             "occurred_at":"2026-07-24T09:00:00Z","observed_at":"2026-07-24T17:00:00Z",
             "changed_fields":["title","labels"],
             "detail":{"item_kind":"task","fields":{"title":{"old":"a","new":"b"}}}}
          ],
          "next_before":null,
          "next_since":"MjAyNi0wNy0yNFQxNzowMDowMFp8NmYxYjRkNWU"
        }}
    """.trimIndent()

    @Test
    fun syncPageParsesBothCursorsAndTheUntypedDetail() = runTest {
        val feed = parse(syncPage)

        assertEquals("MjAyNi0wNy0yNFQxNzowMDowMFp8NmYxYjRkNWU", feed.nextSince)
        // Sync mode nulls the feed cursor. A client that fell back to `next_before` here would silently
        // switch pagination axes mid-catch-up and start skipping rows.
        assertNull(feed.nextBefore)

        val entry = feed.entries.single()
        assertEquals("updated", entry.actionKind)
        assertEquals(listOf("title", "labels"), entry.changedFields)
        // The two instants are NOT interchangeable: occurred_at is the actor's wall-clock (the display
        // and sort axis), observed_at the server clock (the `?since=` axis). This entry is deliberately
        // backdated by eight hours — the offline-phone-flushes-at-5pm case — so a test that confused
        // them would fail here rather than in production.
        assertEquals("2026-07-24T09:00:00Z", entry.occurredAt)
        assertEquals("2026-07-24T17:00:00Z", entry.observedAt)

        val fields = entry.detail!!.jsonObject["fields"]!!.jsonObject
        assertEquals("b", fields["title"]!!.jsonObject["new"]!!.jsonPrimitive.content)
    }

    @Test
    fun feedPageParsesTheScrollBackCursorAndNullsTheSyncOne() = runTest {
        val feed = parse(syncPage.replace(""""next_before":null""", """"next_before":"Y3Vyc29y"""").replace(""""next_since":"MjAyNi0wNy0yNFQxNzowMDowMFp8NmYxYjRkNWU"""", """"next_since":null"""))

        assertEquals("Y3Vyc29y", feed.nextBefore)
        assertNull(feed.nextSince)
    }

    @Test
    fun anEmptyPageParsesToNoEntriesAndNoCursor() = runTest {
        // End-of-feed is an EMPTY entries array, not a null cursor — the last non-empty page still
        // returns one. A pager that stopped on `cursor == null` would spin forever on a quiet ledger,
        // so this is the shape that must terminate it.
        val feed = parse("""{"version":"0.1","data":{"entries":[],"next_before":null,"next_since":null}}""")

        assertTrue(feed.entries.isEmpty())
        assertNull(feed.nextSince)
    }

    @Test
    fun anUnknownActionKindParsesRatherThanFailingThePage() = runTest {
        // The ledger is forensic: a six-month-old build must render an entry whose verb it does not know,
        // because dropping it would silently under-report an audit stream. The wire type is String
        // precisely so this cannot fail — modelling it as an enum would have coerced the token away.
        val feed = parse(syncPage.replace(""""action_kind":"updated"""", """"action_kind":"teleported""""))

        assertEquals("teleported", feed.entries.single().actionKind)
    }

    @Test
    fun aNullDetailParsesWhenTheServerCannotUnwrapTheOrgDek() = runTest {
        // The server degrades an entry it cannot decrypt to `detail: null` rather than dropping the row,
        // so the reader must still surface the plaintext structural fields.
        val feed = parse(syncPage.replace(Regex(""""detail":\{[^\n]*\}\}"""), """"detail":null}"""))

        val entry = feed.entries.single()
        // Kotlin null, NOT JsonNull: the shared reader's `explicitNulls = false` collapses a wire null
        // onto the nullable property, so readers have ONE absent-case to handle rather than two.
        assertNull(entry.detail)
        assertEquals("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee", entry.itemId)
    }

    @Test
    fun absentOptionalFieldsFallBackToTheirDefaults() = runTest {
        // A system-actor row carries no user_id and no provider; a create carries no changed_fields.
        // Tolerating their ABSENCE (not just their nullity) is what keeps an additive backend change
        // from breaking a shipped client.
        val feed = parse(
            """
            {"version":"0.1","data":{"entries":[
              {"entry_id":"1","item_id":"2","action_kind":"created",
               "occurred_at":"2026-07-24T09:00:00Z","observed_at":"2026-07-24T09:00:01Z"}
            ]}}
            """.trimIndent(),
        )

        val entry = feed.entries.single()
        assertNull(entry.userId)
        assertNull(entry.actorKind)
        assertEquals(emptyList(), entry.changedFields)
        assertNull(feed.nextSince)
    }

    private suspend fun parse(body: String): ActivityFeedDto {
        val result = clientReturning(body).requestApi<ActivityFeedDto>()
        if (result !is ApiResult.Success) fail("expected Success but was: $result")
        return result.data
    }

    private fun clientReturning(body: String): HttpClient =
        defernoHttpClient(
            MockEngine { respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json")) },
            DefernoEnvironment.Production,
            BearerTokenProvider { null },
        )
}

package com.circuitstitch.deferno.core.data.plan

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.PlanItemRef
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
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorPlanRemoteSource] (#22, #385) over Ktor's MockEngine (ADR-0006, no real network).
 * Proves it pulls the flat ordered **`/items/plan`** snapshot, keeps just the kind-tagged refs in plan
 * order, and honours the offline-first contract: an error response yields
 * [RemoteSnapshot.Unavailable] so a failed plan refresh leaves the cached plan intact (ADR-0001).
 */
class KtorPlanRemoteSourceTest {

    private val date = LocalDate.parse("2026-06-06")
    private val tz = "America/Chicago"

    private val planEnvelope = """
        {"version":"0.1","data":[
            {"type":"task","id":"c","org_slug":"u-e4h2qk","title":"c","date_created":"2026-05-20T16:11:42Z"},
            {"type":"task","id":"a","org_slug":"u-e4h2qk","title":"a","date_created":"2026-05-20T16:11:42Z"},
            {"type":"task","id":"b","org_slug":"u-e4h2qk","title":"b","date_created":"2026-05-20T16:11:42Z"}
        ]}
    """.trimIndent()

    /**
     * The shape that made the Plan blank: a day of recurring rows. Each carries the inline
     * `today_occurrence` and the redundant `kind` the `/items/plan` seeder attaches — both must pass
     * straight through the tolerant reader without a new DTO.
     */
    private val recurringPlanEnvelope = """
        {"version":"0.1","data":[
            {"type":"habit","kind":"habit","id":"h1","org_slug":"u-e4h2qk","title":"Take a Walk",
             "date_created":"2026-05-20T16:11:42Z",
             "today_occurrence":{"id":"occ-1","parent_id":"h1","scheduled_date":"2026-06-06","status":"open"}},
            {"type":"chore","kind":"chore","id":"c1","org_slug":"u-e4h2qk","title":"Take shot",
             "date_created":"2026-05-20T16:11:42Z"},
            {"type":"event","kind":"event","id":"e1","org_slug":"u-e4h2qk","title":"Standup",
             "date_created":"2026-05-20T16:11:42Z"}
        ]}
    """.trimIndent()

    @Test
    fun fetchPlanKeepsTheOrderedIds() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorPlanRemoteSource(client { req -> captured = req; respondJson(planEnvelope) })

        val refs = (source.fetchPlan(date, tz) as RemoteSnapshot.Available).value

        assertTrue(captured?.url?.encodedPath?.endsWith("/items/plan") == true)
        assertEquals(taskRefs("c", "a", "b"), refs)
    }

    /**
     * **The wire half of the reported bug (#385).** `/tasks/plan` resolves the day's ordered ids against
     * the server's Task store alone and drops the rest, so this exact day came back as `[]`. The
     * polymorphic mirror returns every row tagged by kind, and the tag is what the resolve dispatches
     * on — dropping it is how a Habit came to be looked up in the Task cache and vanish.
     */
    @Test
    fun fetchPlanTagsEachRowWithItsKind() = runTest {
        val source = KtorPlanRemoteSource(client { respondJson(recurringPlanEnvelope) })

        val refs = (source.fetchPlan(date, tz) as RemoteSnapshot.Available).value

        assertEquals(
            listOf(
                PlanItemRef("h1", ItemKind.Habit),
                PlanItemRef("c1", ItemKind.Chore),
                PlanItemRef("e1", ItemKind.Event),
            ),
            refs,
        )
    }

    @Test
    fun fetchPlanPassesTheDayAndZoneAsQueryParameters() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorPlanRemoteSource(client { req -> captured = req; respondJson(planEnvelope) })

        source.fetchPlan(date, tz)

        assertEquals("2026-06-06", captured?.url?.parameters?.get("date"))
        assertEquals(tz, captured?.url?.parameters?.get("tz"))
    }

    @Test
    fun fetchPlanReportsUnavailableOnFailureSoTheCachedPlanStays() = runTest {
        val source = KtorPlanRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetchPlan(date, tz))
    }

    private fun client(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/") }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

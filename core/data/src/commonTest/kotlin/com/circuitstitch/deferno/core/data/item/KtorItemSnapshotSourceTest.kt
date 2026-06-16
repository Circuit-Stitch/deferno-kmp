package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
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
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorItemSnapshotSource] (#226), driven by Ktor's MockEngine on the JVM-fast path
 * (ADR-0006) — no real network. Proves the cold read hits `GET /items`, decodes the polymorphic
 * envelope through the #18 `ItemView` mappers, partitions it into the four kind lists (keying off the
 * injected `type` discriminator and ignoring the redundant `kind`), maps the Task-only
 * `descendant_done`/`descendant_total` (ADR-0034), and honours the offline-first contract (a failure
 * yields [RemoteSnapshot.Unavailable]; a genuine empty list is [RemoteSnapshot.Available] empty).
 */
class KtorItemSnapshotSourceTest {

    private val itemsEnvelope = """
        {"version":"0.1","data":[
            {"type":"task","kind":"task","id":"t","org_slug":"u-e4h2qk","title":"a task",
             "status":"done","sequence":1,"parent_id":"p","date_created":"2026-05-20T16:11:42Z",
             "descendant_done":2,"descendant_total":5},
            {"type":"habit","kind":"habit","id":"h","org_slug":"u-e4h2qk","title":"a habit",
             "status":"active","date_created":"2026-05-04T01:53:05Z","recurrence":{"type":"daily"},
             "series_id":"s-h"},
            {"type":"chore","kind":"chore","id":"c","org_slug":"u-e4h2qk","title":"a chore",
             "status":"active","date_created":"2026-05-12T19:52:01Z","cadence_mode":"rolling",
             "recurrence":{"type":"weekly","days":["Tue"]},"series_id":"s-c"},
            {"type":"event","kind":"event","id":"e","org_slug":"u-e4h2qk","title":"an event",
             "status":"active","all_day":false,"date_created":"2026-05-02T15:00:34Z"}
        ]}
    """.trimIndent()

    @Test
    fun fetchAllHitsTheItemsPathAndPartitionsThePolymorphicSnapshotByKind() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorItemSnapshotSource(client { req -> captured = req; respondJson(itemsEnvelope) })

        val snapshot = (source.fetchAll() as RemoteSnapshot.Available).value

        assertTrue(captured?.url?.encodedPath?.endsWith("/items") == true)
        assertEquals(listOf(TaskId("t")), snapshot.tasks.map { it.id })
        assertEquals(listOf(HabitId("h")), snapshot.habits.map { it.id })
        assertEquals(listOf(ChoreId("c")), snapshot.chores.map { it.id })
        assertEquals(listOf(EventId("e")), snapshot.events.map { it.id })
        // Every /items row is fully hydrated.
        assertEquals(HydrationState.Full, snapshot.tasks.single().hydration)
    }

    @Test
    fun fetchAllMapsTheTaskWorkingStateParentAndDescendantCounts() = runTest {
        val source = KtorItemSnapshotSource(client { respondJson(itemsEnvelope) })

        val taskRow = (source.fetchAll() as RemoteSnapshot.Available).value.tasks.single()

        assertEquals(WorkingState.Done, taskRow.workingState)
        assertEquals(TaskId("p"), taskRow.parentId)
        assertEquals(1L, taskRow.sequence)
        assertEquals(2L, taskRow.descendantDone)
        assertEquals(5L, taskRow.descendantTotal)
    }

    @Test
    fun fetchAllReportsUnavailableOnFailureSoTheCachesStayIntact() = runTest {
        val source = KtorItemSnapshotSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetchAll())
    }

    @Test
    fun fetchAllMapsAGenuineEmptyListToAvailableEmpty_notUnavailable() = runTest {
        // The safety property behind the empty-snapshot purge: a real 200 with data:[] must be
        // Available(empty) — which reconciles and purges the caches — NOT Unavailable (which skips).
        val source = KtorItemSnapshotSource(client { respondJson("""{"version":"0.1","data":[]}""") })

        val result = source.fetchAll()

        assertTrue(result is RemoteSnapshot.Available && result.value == ItemSnapshot())
    }

    // --- test helpers (mirrors KtorTaskRemoteSourceTest) ---

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

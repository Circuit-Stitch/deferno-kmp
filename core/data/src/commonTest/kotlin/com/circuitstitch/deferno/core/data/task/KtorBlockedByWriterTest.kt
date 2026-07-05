package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.create.FakeConnectivity
import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.Task
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
import io.ktor.content.TextContent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Behaviour of [KtorBlockedByWriter] (#291) on the JVM-fast MockEngine path (ADR-0006): the
 * online-only optimistic-apply → `PATCH tasks/{id}` → revert-on-rejection contract. The wire body is
 * asserted verbatim (always-present `blocked_by`, ADR-0011 minimal-body), and the local cache is
 * checked after each verdict — the applied edit survives a 200, is reverted by a 400, and is never
 * touched offline.
 */
class KtorBlockedByWriterTest {

    private val id = TaskId("t-1")

    private fun task(blocked: Boolean = false, blockedBy: List<BlockedByRef> = emptyList()) = Task(
        id = id,
        orgSlug = "u",
        title = "edited",
        workingState = WorkingState.Open,
        dateCreated = Instant.parse("2026-05-01T00:00:00Z"),
        blocked = blocked,
        blockedBy = blockedBy,
    )

    private val okEnvelope = """
        {"version":"0.1","data":{"id":"t-1","org_slug":"u","title":"edited","status":"open","date_created":"2026-05-01T00:00:00Z"}}
    """.trimIndent()

    private val cycleEnvelope = """
        {"version":"0.1","error":{"code":"bad_request","message":"cannot add a blocked_by edge that would form a cycle"}}
    """.trimIndent()

    @Test
    fun appliesOptimisticallyAndPatchesTheOrderedEdgeList() = runTest {
        var captured: HttpRequestData? = null
        val store = FakeTaskLocalStore(mapOf(id to task()))
        val writer = KtorBlockedByWriter(client { req -> captured = req; respondJson(okEnvelope) }, FakeConnectivity(), store)

        val result = writer.setBlockedBy(id, listOf(BlockedByRef("b-1"), BlockedByRef("b-2")))

        assertEquals(BlockedByResult.Applied, result)
        assertEquals(HttpMethod.Patch, captured?.method)
        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t-1") == true)
        assertEquals(
            """{"blocked_by":[{"item":"b-1"},{"item":"b-2"}]}""",
            (captured?.body as TextContent).text,
        )
        // The optimistic apply stands: edges cached in order, the provisional blocked flag set.
        assertEquals(listOf(BlockedByRef("b-1"), BlockedByRef("b-2")), store.all[id]?.blockedBy)
        assertEquals(true, store.all[id]?.blocked)
    }

    @Test
    fun anEmptyListClearsEveryEdgeWithAnAlwaysPresentField() = runTest {
        var captured: HttpRequestData? = null
        val store = FakeTaskLocalStore(mapOf(id to task(blocked = true, blockedBy = listOf(BlockedByRef("b-1")))))
        val writer = KtorBlockedByWriter(client { req -> captured = req; respondJson(okEnvelope) }, FakeConnectivity(), store)

        assertEquals(BlockedByResult.Applied, writer.setBlockedBy(id, emptyList()))

        // ADR-0011: an empty array, never an absent field.
        assertEquals("""{"blocked_by":[]}""", (captured?.body as TextContent).text)
        assertEquals(emptyList(), store.all[id]?.blockedBy)
        assertEquals(false, store.all[id]?.blocked)
    }

    @Test
    fun aServer400RevertsTheOptimisticApplyAndSurfacesTheMessage() = runTest {
        val before = task(blocked = false, blockedBy = emptyList())
        val store = FakeTaskLocalStore(mapOf(id to before))
        val writer = KtorBlockedByWriter(
            client { respondJson(cycleEnvelope, HttpStatusCode.BadRequest) },
            FakeConnectivity(),
            store,
        )

        val result = writer.setBlockedBy(id, listOf(BlockedByRef("b-1")))

        assertEquals(BlockedByResult.Failed("cannot add a blocked_by edge that would form a cycle"), result)
        // The revert restored the exact pre-edit row — no locally-created cycle survives a rejection.
        assertEquals(before, store.all[id])
    }

    @Test
    fun offlineRefusesBeforeAnyApplyOrRequest() = runTest {
        val before = task()
        val store = FakeTaskLocalStore(mapOf(id to before))
        var requests = 0
        val writer = KtorBlockedByWriter(
            client { requests++; respondJson(okEnvelope) },
            FakeConnectivity(online = false),
            store,
        )

        assertEquals(BlockedByResult.Offline, writer.setBlockedBy(id, listOf(BlockedByRef("b-1"))))
        assertEquals(0, requests, "offline must not reach the network")
        assertEquals(before, store.all[id], "offline must not touch the cache")
    }

    @Test
    fun anUncachedRowStillPatchesWithNothingToApplyLocally() = runTest {
        var captured: HttpRequestData? = null
        val store = FakeTaskLocalStore()
        val writer = KtorBlockedByWriter(client { req -> captured = req; respondJson(okEnvelope) }, FakeConnectivity(), store)

        assertEquals(BlockedByResult.Applied, writer.setBlockedBy(id, listOf(BlockedByRef("b-1"))))

        assertTrue(captured?.url?.encodedPath?.endsWith("/tasks/t-1") == true)
        assertTrue(store.all.isEmpty(), "no cached row to transform — the reconcile materialises truth")
    }

    // --- test helpers (the KtorTaskRemoteSourceTest client shape) ---

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

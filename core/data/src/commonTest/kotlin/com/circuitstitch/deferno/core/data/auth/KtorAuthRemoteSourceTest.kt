package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.UserId
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
import kotlin.test.assertIs

/**
 * Behaviour of [KtorAuthRemoteSource] (#20), driven by Ktor's MockEngine on the JVM-fast path
 * (ADR-0006) — no real network. Proves `GET /auth/me` hits the right path, maps the wire envelope
 * through the #18 DTO→domain mapper, and condenses the typed failure shapes to a [MeResult]: a `401`
 * (the verified empty body) → [MeResult.Unauthorized], every other failure → [MeResult.Unavailable].
 */
class KtorAuthRemoteSourceTest {

    // Mirrors contracts/fixtures/auth-me.json (envelope version 0.1, in the supported window).
    private val authMeEnvelope = """
        {"version":"0.1","data":{
            "id":"1d35f62e-eed9-44de-96e8-e61a307af83f",
            "username":"sampleuser","display_name":"Sample User","role":"admin",
            "personal_org_id":"ebca93e5-d663-4624-9fe9-c5361b5b4390","org_slug":"u-e4h2qk",
            "is_admin":false,"console_url":"https://auth2.defernowork.com/ui/console"
        }}
    """.trimIndent()

    @Test
    fun fetchMeMapsTheEnvelopeToAnAuthenticatedDomainUser() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorAuthRemoteSource(client { req -> captured = req; respondJson(authMeEnvelope) })

        val result = source.fetchMe()

        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/me"))
        val user = assertIs<MeResult.Authenticated>(result).user
        assertEquals(UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"), user.id)
        assertEquals("sampleuser", user.username)
        assertEquals("Sample User", user.displayName)
        // personal_org_id is the Org isolation key (ADR-0002) — condensed to a typed OrgId.
        assertEquals(OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"), user.personalOrgId)
    }

    @Test
    fun fetchMeWithACandidateTokenSendsItAsTheBearer() = runTest {
        // #15/ADR-0023: the candidate-token /auth/me carries the pasted PAT as an EXPLICIT bearer (the
        // shared client's plugin then leaves it untouched, DefernoHttpClientTest), validating the token
        // before any Account holds it. This pins the load-bearing `requestMe { bearerAuth(token) }` wiring.
        var captured: HttpRequestData? = null
        val source = KtorAuthRemoteSource(client { req -> captured = req; respondJson(authMeEnvelope) })

        val result = source.fetchMe("pasted-pat-123")

        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/me"))
        assertEquals("Bearer pasted-pat-123", captured?.headers?.get(HttpHeaders.Authorization))
        assertIs<MeResult.Authenticated>(result)
    }

    @Test
    fun emptyBody401MapsToUnauthorized() = runTest {
        // The verified empty-body 401 (CONTRACT-NOTES → "Error model"): synthesized ApiError.Status.
        val source = KtorAuthRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(MeResult.Unauthorized, source.fetchMe())
    }

    @Test
    fun structuredErrorEnvelope401MapsToUnauthorized() = runTest {
        // A 401 that *does* carry an ErrorEnvelope body still means "PAT invalid" → Unauthorized.
        val body = """{"version":"0.1","error":{"code":"unauthorized","message":"token expired"}}"""
        val source = KtorAuthRemoteSource(client { respondJson(body, HttpStatusCode.Unauthorized) })

        assertEquals(MeResult.Unauthorized, source.fetchMe())
    }

    @Test
    fun serverErrorMapsToUnavailableNotUnauthorized() = runTest {
        // A 500 is transient — it must NOT trigger re-auth (the credential is fine).
        val source = KtorAuthRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertEquals(MeResult.Unavailable, source.fetchMe())
    }

    @Test
    fun nonUnauthorizedEndpointErrorMapsToUnavailable() = runTest {
        // A structured non-401 error (e.g. 403) is not a credential-expiry signal → Unavailable.
        val body = """{"version":"0.1","error":{"code":"forbidden","message":"nope"}}"""
        val source = KtorAuthRemoteSource(client { respondJson(body, HttpStatusCode.Forbidden) })

        assertEquals(MeResult.Unavailable, source.fetchMe())
    }

    @Test
    fun outOfWindowEnvelopeVersionMapsToUnavailable() = runTest {
        // A 2xx whose envelope version is outside the supported window (ADR-0005) withholds data as
        // UnsupportedVersion → Unavailable here (not a credential problem, so not Unauthorized).
        val source = KtorAuthRemoteSource(client { respondJson("""{"version":"0.2","data":{}}""") })

        assertEquals(MeResult.Unavailable, source.fetchMe())
    }

    // --- test helpers (mirror KtorTaskRemoteSourceTest) ---

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

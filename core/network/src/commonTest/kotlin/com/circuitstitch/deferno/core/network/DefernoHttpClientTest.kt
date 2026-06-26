package com.circuitstitch.deferno.core.network

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.url
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.appendPathSegments
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of the shared Ktor client + envelope mapping (issue #17), driven by Ktor's
 * MockEngine on the JVM-fast path (ADR-0006) — no real network. Covers the acceptance criteria:
 * authenticated requests carry the Active Account bearer (read fresh per request), cleartext is
 * blocked while HTTPS/loopback pass, and success / error envelopes — including the verified
 * empty `401` — map to typed [ApiResult]. The wire shapes mirror the `contracts/fixtures` JSON.
 */
class DefernoHttpClientTest {

    /** A minimal `@Serializable` payload standing in for a real endpoint DTO (those land later). */
    @Serializable
    private data class Probe(val id: String, val name: String = "fallback")

    private val okEnvelope = """{"version":"0.1","data":{"id":"abc","name":"Sample"}}"""

    // --- AC: success path — envelope unwrapped, tolerant reader (ADR-0005) ---

    @Test
    fun successUnwrapsEnvelopeAndIgnoresUnknownKeys() = runTest {
        // An extra wire key the client doesn't model must not break parsing (additive evolution).
        val body = """{"version":"0.1","data":{"id":"abc","name":"Sample","unknown_field":true}}"""
        val client = client { respondJson(body) }

        val result = client.requestApi<Probe>()

        assertEquals(ApiResult.Success(Probe(id = "abc", name = "Sample")), result)
    }

    @Test
    fun tolerantReaderAppliesDefaultsForAbsentFields() = runTest {
        val client = client { respondJson("""{"version":"0.1","data":{"id":"abc"}}""") }

        val result = client.requestApi<Probe>()

        assertEquals(ApiResult.Success(Probe(id = "abc", name = "fallback")), result)
    }

    // --- AC: authenticated requests carry the Active Account bearer ---

    @Test
    fun attachesBearerFromProvider() = runTest {
        var captured: HttpRequestData? = null
        val client = client(tokenProvider = { "token-a" }) { req ->
            captured = req
            respondJson(okEnvelope)
        }

        client.requestApi<Probe>()

        assertEquals("Bearer token-a", captured?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun readsTokenFreshOnEachRequestSoSwitchingRePointsTheCredential() = runTest {
        // The provider mirrors a live account switch: the same client, a different active token.
        var token: String? = "token-a"
        var captured: HttpRequestData? = null
        val client = client(tokenProvider = { token }) { req ->
            captured = req
            respondJson(okEnvelope)
        }

        client.requestApi<Probe>()
        assertEquals("Bearer token-a", captured?.headers?.get(HttpHeaders.Authorization))

        token = "token-b"
        client.requestApi<Probe>()
        assertEquals("Bearer token-b", captured?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun doesNotOverrideAnExplicitAuthorizationHeader() = runTest {
        // Sign-in validates a CANDIDATE token via /auth/me with an explicit bearer (#15, ADR-0023);
        // the provider's Active-Account token must not clobber it (and the candidate path works even
        // while another Account is active — the add-account precedence).
        var captured: HttpRequestData? = null
        val client = client(tokenProvider = { "active-account-token" }) { req ->
            captured = req
            respondJson(okEnvelope)
        }

        client.requestApi<Probe> { bearerAuth("candidate-token") }

        assertEquals("Bearer candidate-token", captured?.headers?.get(HttpHeaders.Authorization))
    }

    @Test
    fun omitsAuthorizationHeaderWhenNoActiveToken() = runTest {
        var captured: HttpRequestData? = null
        val client = client(tokenProvider = { null }) { req ->
            captured = req
            respondJson(okEnvelope)
        }

        client.requestApi<Probe>()

        assertNull(captured?.headers?.get(HttpHeaders.Authorization))
    }

    // --- AC (#297): the Active-Account session-expiry sink — 401 surfaces, network blip stays silent ---

    @Test
    fun reportsActiveAccountUnauthorizedToTheSessionListener() = runTest {
        val listener = RecordingAuthSessionListener()
        val client = client(tokenProvider = { "active-token" }, sessionListener = listener) {
            respond("", HttpStatusCode.Unauthorized)
        }

        client.requestApi<Probe>()

        assertEquals(listOf(false), listener.authorized) // recorded exactly one "unauthorized"
    }

    @Test
    fun reportsActiveAccountSuccessToTheSessionListenerSoSigningBackInClears() = runTest {
        val listener = RecordingAuthSessionListener()
        val client = client(tokenProvider = { "active-token" }, sessionListener = listener) {
            respondJson(okEnvelope)
        }

        client.requestApi<Probe>()

        assertEquals(listOf(true), listener.authorized) // a 2xx clears any prior expiry
    }

    @Test
    fun doesNotFlagSessionWhenACandidateTokenValidationIs401() = runTest {
        // Sign-in validates a CANDIDATE token with an explicit bearer (#15, ADR-0023). A bad candidate
        // 401s, but that must NOT flag the Active Account's session as expired (it might be a perfectly
        // good account while the user fat-fingers an add-account PAT).
        val listener = RecordingAuthSessionListener()
        val client = client(tokenProvider = { "active-token" }, sessionListener = listener) {
            respond("", HttpStatusCode.Unauthorized)
        }

        client.requestApi<Probe> { bearerAuth("candidate-token") }

        assertTrue(listener.authorized.isEmpty()) // the explicit-bearer request never reports
    }

    @Test
    fun doesNotReportATransportFailureAsAnExpiredSession() = runTest {
        // A blocked cleartext request never reaches the server (no response), so it must not flag the
        // session — offline-first keeps caching silently (AC #5). Same shape as a timeout/TLS failure.
        val listener = RecordingAuthSessionListener()
        val client = client(tokenProvider = { "active-token" }, sessionListener = listener) {
            respondJson(okEnvelope)
        }

        client.requestApi<Probe> { url("http://insecure.example.com/probe") }

        assertTrue(listener.authorized.isEmpty())
    }

    @Test
    fun doesNotFlagSessionForAnUnauthenticatedRequest() = runTest {
        // No Active Account (provider → null): the request goes out bare, and a 401 there is "not signed
        // in", the auth flow's concern — not an expired-session banner on the read surfaces.
        val listener = RecordingAuthSessionListener()
        val client = client(tokenProvider = { null }, sessionListener = listener) {
            respond("", HttpStatusCode.Unauthorized)
        }

        client.requestApi<Probe>()

        assertTrue(listener.authorized.isEmpty())
    }

    // --- AC: cleartext disabled; HTTPS enforced ---

    @Test
    fun blocksCleartextRequestToRemoteHost() = runTest {
        val client = client { respondJson(okEnvelope) }

        val result = client.requestApi<Probe> { url("http://insecure.example.com/probe") }

        val failure = assertIs<ApiResult.Failure>(result)
        val transport = assertIs<ApiError.Transport>(failure.error)
        assertIs<CleartextNotPermittedException>(transport.cause)
    }

    @Test
    fun appendsEndpointPathOntoTheApiPrefixedBaseUrl() = runTest {
        // The base URL carries the `/api` prefix and a trailing slash; an endpoint appends its path
        // onto it. Without the trailing slash, Ktor's relative resolution drops `/api` and the request
        // misses the API entirely (verified live against staging, #20) — this pins `/api/...` for every
        // environment so a dropped slash regresses here rather than only at runtime.
        DefernoEnvironment.entries.forEach { environment ->
            var captured: HttpRequestData? = null
            val client = client(environment = environment) { req -> captured = req; respondJson(okEnvelope) }

            client.requestApi<Probe> { url { appendPathSegments("auth", "me") } }

            assertEquals("/api/auth/me", captured?.url?.encodedPath, "wrong path for ${environment.name}")
        }
    }

    @Test
    fun allowsHttpsRequestToTheConfiguredBaseUrl() = runTest {
        // No URL override → the request uses the (https) production base. That it is NOT blocked
        // proves the guard sees the base URL merged in by defaultRequest.
        val client = client(environment = DefernoEnvironment.Production) { respondJson(okEnvelope) }

        val result = client.requestApi<Probe>()

        assertIs<ApiResult.Success<Probe>>(result)
    }

    @Test
    fun allowsCleartextToLoopbackForLocalDevelopment() = runTest {
        // The whole loopback allow-list: hostname, IPv4 literal, and bracketed IPv6 literal.
        val loopbacks = listOf(
            "http://localhost:3000/api/probe",
            "http://127.0.0.1:3000/api/probe",
            "http://[::1]:3000/api/probe",
        )
        loopbacks.forEach { loopbackUrl ->
            val client = client(environment = DefernoEnvironment.Local) { respondJson(okEnvelope) }

            val result = client.requestApi<Probe> { url(loopbackUrl) }

            assertIs<ApiResult.Success<Probe>>(result)
        }
    }

    // --- AC: error envelopes mapped to typed failures ---

    @Test
    fun mapsErrorEnvelopeToEndpointFailure() = runTest {
        // Mirrors contracts/fixtures/error-404.json.
        val body = """{"version":"0.1","error":{"code":"not_found","message":"task not found"}}"""
        val client = client { respondJson(body, HttpStatusCode.NotFound) }

        val result = client.requestApi<Probe>()

        assertEquals(
            ApiResult.Failure(ApiError.Endpoint(status = 404, code = "not_found", message = "task not found")),
            result,
        )
    }

    @Test
    fun endpointFailureCarriesTheActualHttpStatusNotAConstant() = runTest {
        // A second status on the Endpoint path proves the status is threaded from the response.
        val body = """{"version":"0.1","error":{"code":"unprocessable","message":"bad input"}}"""
        val client = client { respondJson(body, HttpStatusCode.UnprocessableEntity) }

        val result = client.requestApi<Probe>()

        assertEquals(
            ApiResult.Failure(ApiError.Endpoint(status = 422, code = "unprocessable", message = "bad input")),
            result,
        )
    }

    @Test
    fun synthesizesStatusFailureWhenJsonBodyIsNotAnErrorEnvelope() = runTest {
        // Valid JSON, but not the {error:{code,message}} shape → degrade to Status, not a
        // half-populated Endpoint (CONTRACT-NOTES.md: synthesize when the body is unparseable).
        val client = client { respondJson("""{"version":"0.1"}""", HttpStatusCode.BadRequest) }

        val result = client.requestApi<Probe>()

        assertEquals(ApiResult.Failure(ApiError.Status(status = 400, message = "Bad Request")), result)
    }

    @Test
    fun synthesizesStatusFailureForEmptyBody401() = runTest {
        // The verified empty-body 401 (CONTRACT-NOTES.md): no ErrorEnvelope to parse.
        val client = client { respond("", HttpStatusCode.Unauthorized) }

        val result = client.requestApi<Probe>()

        assertEquals(ApiResult.Failure(ApiError.Status(status = 401, message = "Unauthorized")), result)
    }

    @Test
    fun synthesizesStatusFailureWhenErrorBodyIsUnparseable() = runTest {
        val client = client { respond("upstream boom", HttpStatusCode.InternalServerError) }

        val result = client.requestApi<Probe>()

        assertEquals(ApiResult.Failure(ApiError.Status(status = 500, message = "Internal Server Error")), result)
    }

    @Test
    fun mapsMalformedSuccessBodyToTransportFailure() = runTest {
        val client = client { respondJson("this is not json") }

        val result = client.requestApi<Probe>()

        val failure = assertIs<ApiResult.Failure>(result)
        assertIs<ApiError.Transport>(failure.error)
    }

    // --- AC (ADR-0005, #18): version-window gate with the force-upgrade signal split ---

    @Test
    fun versionAboveMaxIsAForceUpgradeSignal() = runTest {
        // A server version ABOVE the window (0.2 while [0.1..0.1]) is an unknown breaking major the
        // client can't parse safely → ForceUpgrade, so a caller can show "update required" (#18).
        val client = client { respondJson("""{"version":"0.2","data":{"id":"abc"}}""") }

        val result = client.requestApi<Probe>()

        assertEquals(
            ApiResult.Failure(ApiError.UnsupportedVersion("0.2", ApiError.UnsupportedVersion.Kind.ForceUpgrade)),
            result,
        )
    }

    @Test
    fun versionBelowMinIsUnsupportedNotForceUpgrade() = runTest {
        // A server version BELOW the window (0.0) is too old to map — degrade/refuse, not upgrade.
        val client = client { respondJson("""{"version":"0.0","data":{"id":"abc"}}""") }

        val result = client.requestApi<Probe>()

        assertEquals(
            ApiResult.Failure(ApiError.UnsupportedVersion("0.0", ApiError.UnsupportedVersion.Kind.Unsupported)),
            result,
        )
    }

    @Test
    fun unparseableVersionIsUnparseableKind() = runTest {
        // A non-`major.minor` version can't be placed against the window at all (#18: logged-as-
        // unknown signal) → Unparseable, distinct from a known-but-out-of-window version.
        val client = client { respondJson("""{"version":"garbage","data":{"id":"abc"}}""") }

        val result = client.requestApi<Probe>()

        assertEquals(
            ApiResult.Failure(ApiError.UnsupportedVersion("garbage", ApiError.UnsupportedVersion.Kind.Unparseable)),
            result,
        )
    }

    @Test
    fun rejectsOutOfWindowVersionEvenWhenThePayloadShapeAlsoChanged() = runTest {
        // The realistic breaking bump: a new major changes BOTH the version AND the data shape
        // (here `id` becomes an object, incompatible with Probe). The version must be read before
        // `data` is bound, so this stays a ForceUpgrade UnsupportedVersion — not a generic
        // Transport/parse error (ADR-0005: withhold possibly-misparsed data).
        val client = client { respondJson("""{"version":"0.2","data":{"id":{"nested":true}}}""") }

        val result = client.requestApi<Probe>()

        assertEquals(
            ApiResult.Failure(ApiError.UnsupportedVersion("0.2", ApiError.UnsupportedVersion.Kind.ForceUpgrade)),
            result,
        )
    }

    // --- test helpers ---

    private fun client(
        environment: DefernoEnvironment = DefernoEnvironment.Production,
        tokenProvider: BearerTokenProvider = BearerTokenProvider { null },
        sessionListener: AuthSessionListener = AuthSessionListener.Noop,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = defernoHttpClient(MockEngine(handler), environment, tokenProvider, sessionListener)

    /** Records each session-outcome callback as `true` (authorized/2xx) or `false` (unauthorized/401). */
    private class RecordingAuthSessionListener : AuthSessionListener {
        val authorized = mutableListOf<Boolean>()
        override fun onActiveSessionUnauthorized() { authorized += false }
        override fun onActiveSessionAuthorized() { authorized += true }
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

package com.circuitstitch.deferno.core.data.security

import com.circuitstitch.deferno.core.model.ConnectedDevice
import com.circuitstitch.deferno.core.model.MfaStatus
import com.circuitstitch.deferno.core.model.TotpEnrollment
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
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/**
 * Behaviour of [KtorSecurityRemoteSource], driven by Ktor's MockEngine on the JVM-fast path
 * (ADR-0006) — no real network. Pins three things the Security & 2FA flow leans on:
 *
 * - the endpoint wiring (paths, methods, request bodies) and the envelope→domain condensation
 *   to a [SecurityResult] (ADR-0011: wire shapes stay below this seam);
 * - **the step-up cookie echo** — the shared client keeps no cookie jar (deliberately, see the
 *   source's KDoc), so the source itself must capture step-up's `Set-Cookie` pairs and replay
 *   them on every subsequent `/auth/mfa/…` mutation, a rotated same-named cookie *replacing*
 *   its predecessor rather than stacking;
 * - the status-code split step-up's semantics force: step-up's own `401` is a wrong *password*
 *   ([SecurityResult.Rejected] — the PAT already authenticated to reach the handler) while a
 *   `401` on any other call is a PAT problem ([SecurityResult.Unauthorized], the re-auth
 *   signal), and revoke's `404` converges to Success (already revoked).
 */
class KtorSecurityRemoteSourceTest {

    // Mirrors the "Security & 2FA" contract shapes (envelope version 0.1, in the supported window).
    private val statusEnvelope =
        """{"version":"0.1","data":{"mfa_enabled":true,"email_backup":false}}"""
    private val enrollStartEnvelope =
        """{"version":"0.1","data":{"secret":"S","uri":"otpauth://totp/Deferno:sampleuser?secret=S"}}"""
    private val enrollVerifyEnvelope =
        """{"version":"0.1","data":{"mfa_enabled":true,"primary":"totp","recovery_codes":["aaaaa-bbbbb","ccccc-ddddd"]}}"""
    private val stepUpEnvelope =
        """{"version":"0.1","data":{"stepped_up_at":1750000000}}"""

    @Test
    fun fetchStatusMapsTheEnvelopeToDomainMfaStatus() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorSecurityRemoteSource(client { req -> captured = req; respondJson(statusEnvelope) })

        val result = source.fetchStatus()

        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/mfa/status"))
        assertEquals(
            MfaStatus(totpEnabled = true, emailBackup = false),
            assertIs<SecurityResult.Success<MfaStatus>>(result).value,
        )
    }

    @Test
    fun stepUpPostsThePasswordAndItsSessionCookiesRideSubsequentMutations() = runTest {
        // THE load-bearing behavior: the shared client keeps no cookie jar, so the source itself
        // must capture step-up's Set-Cookie pairs (attribute-stripped) and echo them on the
        // step-up-gated /auth/mfa/… mutations — otherwise every mutation 403s forever.
        val requests = mutableListOf<HttpRequestData>()
        val source = KtorSecurityRemoteSource(
            client { req ->
                requests += req
                if (req.url.encodedPath.endsWith("/auth/step-up")) {
                    respond(
                        stepUpEnvelope,
                        HttpStatusCode.OK,
                        headersOf(
                            HttpHeaders.SetCookie,
                            listOf("id=abc123; Path=/; HttpOnly", "sid=zzz; Path=/"),
                        ),
                    )
                } else {
                    respondJson(enrollStartEnvelope)
                }
            },
        )

        val stepUp = source.stepUp("hunter2")
        source.enrollStart()

        assertIs<SecurityResult.Success<Unit>>(stepUp)
        val (stepUpRequest, enrollRequest) = requests
        assertEquals("POST", stepUpRequest.method.value)
        assertEquals(true, stepUpRequest.url.encodedPath.endsWith("/auth/step-up"))
        assertEquals("""{"password":"hunter2"}""", stepUpRequest.bodyText())
        // BOTH cookies echo on the gated mutation, name=value only (no Path/HttpOnly attributes).
        val cookie = enrollRequest.headers[HttpHeaders.Cookie].orEmpty()
        assertTrue("id=abc123" in cookie, "expected the id cookie in: $cookie")
        assertTrue("sid=zzz" in cookie, "expected the sid cookie in: $cookie")
    }

    @Test
    fun aRepeatStepUpReplacesTheSameNamedCookieInsteadOfStacking() = runTest {
        // A rotated session cookie must supersede its predecessor — echoing a stale duplicate
        // alongside the fresh one would make the server's pick undefined.
        var stepUps = 0
        val requests = mutableListOf<HttpRequestData>()
        val source = KtorSecurityRemoteSource(
            client { req ->
                requests += req
                if (req.url.encodedPath.endsWith("/auth/step-up")) {
                    stepUps++
                    respond(
                        stepUpEnvelope,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.SetCookie, listOf("id=value$stepUps; Path=/; HttpOnly")),
                    )
                } else {
                    respondJson(enrollStartEnvelope)
                }
            },
        )

        source.stepUp("hunter2")
        source.stepUp("hunter2")
        source.enrollStart()

        // Exactly the second value — the first neither lingers nor stacks.
        assertEquals("id=value2", requests.last().headers[HttpHeaders.Cookie])
    }

    @Test
    fun stepUp401MapsToRejectedNotUnauthorized() = runTest {
        // Step-up's 401 means "wrong password" (or attempt budget exhausted) — the PAT already
        // authenticated to reach the handler, so it must NOT surface as Unauthorized (which
        // would trip per-Account re-auth upstream and sign the user out over a typo).
        val body = """{"version":"0.1","error":{"code":"unauthorized","message":"invalid credentials"}}"""
        val source = KtorSecurityRemoteSource(client { respondJson(body, HttpStatusCode.Unauthorized) })

        assertEquals(SecurityResult.Rejected, source.stepUp("wrong-password"))
    }

    @Test
    fun stepUpTransportFailureMapsToUnavailable() = runTest {
        val source = KtorSecurityRemoteSource(client { throw RuntimeException("connection refused") })

        assertEquals(SecurityResult.Unavailable, source.stepUp("hunter2"))
    }

    @Test
    fun enrollStartBeforeAnyStepUpSendsNoCookieHeader() = runTest {
        // No stamp captured yet → no Cookie header at all (not an empty one).
        var captured: HttpRequestData? = null
        val source = KtorSecurityRemoteSource(client { req -> captured = req; respondJson(enrollStartEnvelope) })

        source.enrollStart()

        assertNull(captured?.headers?.get(HttpHeaders.Cookie))
    }

    @Test
    fun enrollStart403StepUpRequiredMapsToStepUpRequired() = runTest {
        // The typed "re-verify your password first" refusal — the flow's resume signal.
        val body =
            """{"version":"0.1","error":{"code":"step_up_required","message":"step-up authentication required","step_up_required":true}}"""
        val source = KtorSecurityRemoteSource(client { respondJson(body, HttpStatusCode.Forbidden) })

        assertEquals(SecurityResult.StepUpRequired, source.enrollStart())
    }

    @Test
    fun enrollStartMapsTheEnvelopeToATotpEnrollment() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorSecurityRemoteSource(client { req -> captured = req; respondJson(enrollStartEnvelope) })

        val result = source.enrollStart()

        assertEquals("POST", captured?.method?.value)
        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/mfa/enroll/start"))
        assertEquals(
            TotpEnrollment(secret = "S", uri = "otpauth://totp/Deferno:sampleuser?secret=S"),
            assertIs<SecurityResult.Success<TotpEnrollment>>(result).value,
        )
    }

    @Test
    fun enrollVerifyPostsTheCodeAndMapsTheRecoveryCodes() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorSecurityRemoteSource(client { req -> captured = req; respondJson(enrollVerifyEnvelope) })

        val result = source.enrollVerify("123456")

        assertEquals("POST", captured?.method?.value)
        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/mfa/enroll/verify"))
        assertEquals("""{"code":"123456"}""", captured?.bodyText())
        // The single-use recovery codes — shown exactly once, so losing them here loses them for good.
        assertEquals(
            listOf("aaaaa-bbbbb", "ccccc-ddddd"),
            assertIs<SecurityResult.Success<List<String>>>(result).value,
        )
    }

    @Test
    fun enrollVerify400InvalidCodeMapsToRejected() = runTest {
        // A wrong TOTP code is an input refusal (re-enter, same secret) — not transient, not a PAT problem.
        val body = """{"version":"0.1","error":{"code":"bad_request","message":"invalid code"}}"""
        val source = KtorSecurityRemoteSource(client { respondJson(body, HttpStatusCode.BadRequest) })

        assertEquals(SecurityResult.Rejected, source.enrollVerify("000000"))
    }

    @Test
    fun enrollVerifyEmptyBody401MapsToUnauthorized() = runTest {
        // Unlike step-up's, a 401 on the gated mutations IS a PAT problem (the verified
        // empty-body 401, CONTRACT-NOTES → "Error model": synthesized ApiError.Status).
        val source = KtorSecurityRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(SecurityResult.Unauthorized, source.enrollVerify("123456"))
    }

    @Test
    fun theRemainingMutationsPostToTheirEndpointsAndCondenseToUnit() = runTest {
        // disable / backup-add / backup-remove share one shape: a POST whose success body carries
        // nothing the client keeps — condensed to Success(Unit).
        val cases: List<Triple<String, String, suspend (SecurityRemoteSource) -> SecurityResult<Unit>>> = listOf(
            Triple(
                "/auth/mfa/disable",
                """{"version":"0.1","data":{"mfa_enabled":false}}""",
                { source -> source.disableMfa() },
            ),
            Triple(
                "/auth/mfa/backup/add",
                """{"version":"0.1","data":{"backup":"email"}}""",
                { source -> source.addEmailBackup() },
            ),
            Triple(
                "/auth/mfa/backup/remove",
                """{"version":"0.1","data":{"backup":"removed"}}""",
                { source -> source.removeEmailBackup() },
            ),
        )
        cases.forEach { (path, envelope, call) ->
            var captured: HttpRequestData? = null
            val source = KtorSecurityRemoteSource(client { req -> captured = req; respondJson(envelope) })

            val result = call(source)

            assertEquals("POST", captured?.method?.value, path)
            assertEquals(true, captured?.url?.encodedPath?.endsWith(path), path)
            assertIs<SecurityResult.Success<Unit>>(result, path)
        }
    }

    @Test
    fun fetchConnectedDevicesMapsTheEnvelopeToDomainDevices() = runTest {
        val envelope = """
            {"version":"0.1","data":[{
                "id":"11111111-1111-1111-1111-111111111111",
                "name":"Deferno Android — Pixel 8","kind":"user",
                "created_at":"2026-06-15T10:30:00Z","last_used_at":null
            }]}
        """.trimIndent()
        var captured: HttpRequestData? = null
        val source = KtorSecurityRemoteSource(client { req -> captured = req; respondJson(envelope) })

        val result = source.fetchConnectedDevices()

        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/connected-devices"))
        // ISO-8601 → typed Instant at the seam; a never-presented token keeps lastUsedAt null.
        assertEquals(
            listOf(
                ConnectedDevice(
                    id = "11111111-1111-1111-1111-111111111111",
                    name = "Deferno Android — Pixel 8",
                    createdAt = Instant.parse("2026-06-15T10:30:00Z"),
                    lastUsedAt = null,
                ),
            ),
            assertIs<SecurityResult.Success<List<ConnectedDevice>>>(result).value,
        )
    }

    @Test
    fun revokeDeviceDeletesByIdWithNoExplicitBearer() = runTest {
        var captured: HttpRequestData? = null
        // 204 No Content — revoke deliberately bypasses requestApi (no envelope to parse).
        val source = KtorSecurityRemoteSource(client { req -> captured = req; respond("", HttpStatusCode.NoContent) })

        val result = source.revokeDevice("tok-456")

        assertEquals("DELETE", captured?.method?.value)
        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/tokens/tok-456"))
        // No EXPLICIT Authorization: in production the shared client's bearer plugin attaches the
        // ACTIVE Account's PAT (which is what authorizes revoking a *different* device's token) —
        // unlike the sign-out self-revoke's explicit bearer. The bare test client attaches none.
        assertNull(captured?.headers?.get(HttpHeaders.Authorization))
        assertIs<SecurityResult.Success<Unit>>(result)
    }

    @Test
    fun revokeDevice404MapsToSuccessAlreadyRevoked() = runTest {
        // 404 = already revoked — the outcome the caller wanted; converging beats erroring.
        val source = KtorSecurityRemoteSource(client { respond("", HttpStatusCode.NotFound) })

        assertIs<SecurityResult.Success<Unit>>(source.revokeDevice("tok-456"))
    }

    @Test
    fun revokeDevice401MapsToUnauthorized() = runTest {
        val source = KtorSecurityRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(SecurityResult.Unauthorized, source.revokeDevice("tok-456"))
    }

    @Test
    fun revokeDevice500MapsToUnavailable() = runTest {
        // A 500 is transient — it must NOT trigger re-auth (the credential is fine).
        val source = KtorSecurityRemoteSource(client { respond("", HttpStatusCode.InternalServerError) })

        assertEquals(SecurityResult.Unavailable, source.revokeDevice("tok-456"))
    }

    // --- test helpers (mirror KtorAuthRemoteSourceTest) ---

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

    private suspend fun HttpRequestData.bodyText(): String =
        (body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            ?: (body as? io.ktor.http.content.TextContent)?.text
            ?: ""
}

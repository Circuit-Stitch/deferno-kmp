package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.DefernoEnvironment
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
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Behaviour of [KtorNativeAuthRemoteSource] (#15, ADR-0026) over Ktor's MockEngine on the JVM-fast
 * path (ADR-0006). Pins the verified wire contract (`contracts/CONTRACT-NOTES.md` → "Native browser
 * sign-in"): registration POSTs RFC 7591 and reads `client_id`; the authorize URL carries the S256
 * PKCE + state params; the token exchange POSTs `{code, code_verifier, client_id, redirect_uri, name}`
 * and reads the minted `token` + `id`; a `400 invalid grant` surfaces as a typed failure. No bearer
 * header is ever attached (these run before any Account exists).
 */
class KtorNativeAuthRemoteSourceTest {

    private val redirectUri = "com.circuitstitch.deferno://auth"

    @Test
    fun registerPostsRfc7591AndReadsClientId() = runTest {
        var captured: HttpRequestData? = null
        var body = ""
        val source = source { req -> captured = req; body = req.bodyText(); respondJson(REGISTER_RESPONSE, HttpStatusCode.Created) }

        val result = source.register(redirectUri, clientName = "Deferno Android — Pixel 8")

        assertEquals(HttpMethod.Post, captured?.method)
        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/native/register"))
        assertNull(captured?.headers?.get(HttpHeaders.Authorization), "registration must be unauthenticated")
        assertTrue(body.contains("\"redirect_uris\""), body)
        assertTrue(body.contains(redirectUri), body)
        assertTrue(body.contains("\"token_endpoint_auth_method\":\"none\""), body)
        assertEquals("5e1ec06c-c77b-4bba-b9c8-a34744c0bf1f", assertIs<ApiResult.Success<ClientRegistration>>(result).data.clientId)
    }

    @Test
    fun authorizeUrlCarriesPkceAndStateParams() {
        val source = source { respondJson("{}") }

        val url = Url(source.authorizeUrl(clientId = "cid-1", redirectUri = redirectUri, codeChallenge = "chal-xyz", state = "state-abc"))

        assertTrue(url.encodedPath.endsWith("/auth/native/authorize"), url.toString())
        val q = url.parameters
        assertEquals("cid-1", q["client_id"])
        assertEquals(redirectUri, q["redirect_uri"])
        assertEquals("code", q["response_type"])
        assertEquals("chal-xyz", q["code_challenge"])
        assertEquals("S256", q["code_challenge_method"])
        assertEquals("state-abc", q["state"])
    }

    @Test
    fun exchangeCodePostsTheBindingFieldsAndReadsTheMintedToken() = runTest {
        var captured: HttpRequestData? = null
        var body = ""
        val source = source { req -> captured = req; body = req.bodyText(); respondJson(TOKEN_RESPONSE) }

        val result = source.exchangeCode(
            code = "code-1", codeVerifier = "verifier-1", clientId = "cid-1",
            redirectUri = redirectUri, deviceName = "Deferno Android — Pixel 8",
        )

        assertEquals(HttpMethod.Post, captured?.method)
        assertEquals(true, captured?.url?.encodedPath?.endsWith("/auth/native/token"))
        assertNull(captured?.headers?.get(HttpHeaders.Authorization), "exchange must be unauthenticated")
        assertTrue(body.contains("\"code\":\"code-1\""), body)
        assertTrue(body.contains("\"code_verifier\":\"verifier-1\""), body)
        assertTrue(body.contains("\"client_id\":\"cid-1\""), body)
        assertTrue(body.contains("\"redirect_uri\""), body)
        val minted = assertIs<ApiResult.Success<MintedToken>>(result).data
        assertEquals("pat-secret-shown-once", minted.token)
        assertEquals("7c9a1b2d-token-id", minted.tokenId)
    }

    @Test
    fun exchangeCodeMapsInvalidGrantToAFailure() = runTest {
        val body = """{"version":"0.1","error":{"code":"bad_request","message":"invalid grant"}}"""
        val source = source { respondJson(body, HttpStatusCode.BadRequest) }

        val result = source.exchangeCode("bad", "v", "cid", redirectUri, "dev")

        val error = assertIs<ApiResult.Failure>(result).error
        assertEquals(400, assertIs<ApiError.Endpoint>(error).status)
    }

    // --- helpers (mirror KtorAuthRemoteSourceTest) ---

    private fun source(
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): KtorNativeAuthRemoteSource = KtorNativeAuthRemoteSource(
        client = HttpClient(MockEngine(handler)) {
            expectSuccess = false
            install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            defaultRequest { url("https://app2.defernowork.com/api/") }
        },
        environment = DefernoEnvironment.Staging,
    )

    private fun MockRequestHandleScope.respondJson(body: String, status: HttpStatusCode = HttpStatusCode.OK) =
        respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

    private suspend fun HttpRequestData.bodyText(): String =
        (body as? io.ktor.http.content.OutgoingContent.ByteArrayContent)?.bytes()?.decodeToString()
            ?: (body as? io.ktor.http.content.TextContent)?.text
            ?: ""

    private companion object {
        const val REGISTER_RESPONSE =
            """{"version":"0.1","data":{"client_id":"5e1ec06c-c77b-4bba-b9c8-a34744c0bf1f","client_name":"Deferno Android","redirect_uris":["com.circuitstitch.deferno://auth"],"token_endpoint_auth_method":"none"}}"""
        const val TOKEN_RESPONSE =
            """{"version":"0.1","data":{"id":"7c9a1b2d-token-id","name":"Deferno Android — Pixel 8","kind":"user","created_at":"2026-06-10T00:00:00Z","client_id":"5e1ec06c-c77b-4bba-b9c8-a34744c0bf1f","last_used_at":null,"token":"pat-secret-shown-once"}}"""
    }
}

package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.AccountBearerTokenProvider
import com.circuitstitch.deferno.core.data.account.DefaultAccountManager
import com.circuitstitch.deferno.core.data.account.FakeAccountDataStore
import com.circuitstitch.deferno.core.data.account.InMemoryAccountRegistry
import com.circuitstitch.deferno.core.data.account.RecordingReauthRequester
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.network.BearerTokenProvider
import com.circuitstitch.deferno.core.secure.InMemorySecretVault
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.client.request.bearerAuth
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
import kotlin.test.assertTrue

/**
 * The #20 tracer bullet end-to-end on the JVM-fast path (ADR-0006), driven by Ktor's MockEngine —
 * the data-layer chain wired from the **real** account + repository collaborators:
 *
 *   AccountManager (vaulted PAT) → AccountBearerTokenProvider → bearer plugin →
 *   `GET /auth/me` → envelope unwrap + ADR-0005 version gate → AuthenticatedUserDto → domain User →
 *   DefaultAuthRepository → (on 401) re-auth request scoped to the Active Account.
 *
 * The load-bearing collaborator under test is the real [AccountBearerTokenProvider] resolving the
 * Active Account's PAT fresh per request, so the Account→credential flow is genuine. The **shipping
 * `HttpClient` itself is NOT exercised here**: production's `defernoHttpClient` builder is `internal`
 * to `core:network` and takes no test engine, so [authClient] *mirrors* (does not reuse) the bearer
 * plugin and content negotiation. The production client wiring — the real `DefernoBearerAuth` plugin,
 * the cleartext guard, the version gate — is exercised in `core:network`'s `DefernoHttpClientTest`
 * (over MockEngine) and against the real engine in `StagingAuthMeIntegrationTest`.
 *
 * What this proves: the seams compose — the Active Account's PAT really rides on the request (not
 * just in a unit of the provider), the 0.1 envelope is unwrapped to a domain
 * [com.circuitstitch.deferno.core.model.User], and a `401` routes **only** the Active Account to
 * re-auth (a sibling Account is never flagged, ADR-0002 hard isolation), with a fast user switch
 * re-pointing which Account a 401 implicates.
 */
class AuthMeEndToEndTest {

    private val authMeEnvelope = """
        {"version":"0.1","data":{
            "id":"1d35f62e-eed9-44de-96e8-e61a307af83f",
            "username":"sampleuser","display_name":"Sample User","role":"admin",
            "personal_org_id":"ebca93e5-d663-4624-9fe9-c5361b5b4390","org_slug":"u-e4h2qk",
            "is_admin":false,"console_url":"https://auth2.defernowork.com/ui/console"
        }}
    """.trimIndent()

    // Two isolated Accounts: acc-1 is added first so it is the Active Account; acc-2 is a sibling.
    private fun fixture(): Fixture {
        val vault = InMemorySecretVault()
        val manager = DefaultAccountManager(InMemoryAccountRegistry(), vault, FakeAccountDataStore())
        val reauth = RecordingReauthRequester()
        return Fixture(vault, manager, reauth)
    }

    private class Fixture(
        val vault: InMemorySecretVault,
        val manager: DefaultAccountManager,
        val reauth: RecordingReauthRequester,
    ) {
        val tokenProvider: BearerTokenProvider = AccountBearerTokenProvider(manager, vault)
    }

    @Test
    fun signedInAccountFetchesAuthMeWithItsPatAndRendersTheUser() = runTest {
        val f = fixture()
        f.manager.addAccount(Account(AccountId("acc-1"), "Work"), token = "pat-active")
        f.manager.addAccount(Account(AccountId("acc-2"), "Personal"), token = "pat-other")

        var captured: HttpRequestData? = null
        val repo = repository(f, reauth = f.reauth) { req -> captured = req; respondJson(authMeEnvelope) }

        val result = repo.loadMe()

        // The Active Account's PAT (acc-1's) really rode on the wire request.
        assertEquals("/auth/me", captured?.url?.encodedPath)
        assertEquals("Bearer pat-active", captured?.headers?.get(HttpHeaders.Authorization))
        // The 0.1 envelope was unwrapped + condensed to the domain User.
        val user = assertIs<MeResult.Authenticated>(result).user
        assertEquals("sampleuser", user.username)
        assertEquals("Sample User", user.displayName)
        assertEquals(OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"), user.personalOrgId)
        // A success never flags re-auth.
        assertTrue(f.reauth.requested.isEmpty())
    }

    @Test
    fun a401RoutesOnlyTheActiveAccountToReauth() = runTest {
        val f = fixture()
        f.manager.addAccount(Account(AccountId("acc-1"), "Work"), token = "pat-active")
        f.manager.addAccount(Account(AccountId("acc-2"), "Personal"), token = "pat-other")

        var captured: HttpRequestData? = null
        val repo = repository(f, reauth = f.reauth) { req -> captured = req; respond("", HttpStatusCode.Unauthorized) }

        val result = repo.loadMe()

        assertEquals(MeResult.Unauthorized, result)
        // It was acc-1's PAT that 401'd, and exactly acc-1 is flagged — the sibling is untouched.
        assertEquals("Bearer pat-active", captured?.headers?.get(HttpHeaders.Authorization))
        assertEquals(listOf(AccountId("acc-1")), f.reauth.requested)
    }

    @Test
    fun afterAFastUserSwitchA401ImplicatesTheNewlyActiveAccount() = runTest {
        val f = fixture()
        f.manager.addAccount(Account(AccountId("acc-1"), "Work"), token = "pat-active")
        f.manager.addAccount(Account(AccountId("acc-2"), "Personal"), token = "pat-other")
        f.manager.switchTo(AccountId("acc-2"))

        var captured: HttpRequestData? = null
        val repo = repository(f, reauth = f.reauth) { req -> captured = req; respond("", HttpStatusCode.Unauthorized) }

        repo.loadMe()

        // The same client re-pointed at acc-2's PAT (read fresh per request), and the 401 flags acc-2.
        assertEquals("Bearer pat-other", captured?.headers?.get(HttpHeaders.Authorization))
        assertEquals(listOf(AccountId("acc-2")), f.reauth.requested)
    }

    // --- test helpers ---

    /** Wires the production repository over a MockEngine client that mirrors the shipping bearer plugin. */
    private fun repository(
        f: Fixture,
        reauth: RecordingReauthRequester,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): DefaultAuthRepository {
        val client = authClient(f.tokenProvider, handler)
        return DefaultAuthRepository(KtorAuthRemoteSource(client), f.manager, reauth)
    }

    /**
     * A MockEngine client carrying the same bearer behaviour the production [defernoHttpClient] ships:
     * the Active Account's token (resolved fresh per request through the provider) is attached as
     * `Authorization: Bearer`. (The production cleartext + version pipeline is covered in core:network;
     * here we only need the bearer seam to prove the Account→request credential flow end-to-end.)
     */
    private fun authClient(
        tokenProvider: BearerTokenProvider,
        handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler)) {
        expectSuccess = false
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        defaultRequest { url("https://api.example.test/") }
        install(
            createClientPlugin("TestBearerAuth") {
                onRequest { request, _ -> tokenProvider.currentToken()?.let { request.bearerAuth(it) } }
            },
        )
    }

    private fun MockRequestHandleScope.respondJson(
        body: String,
        status: HttpStatusCode = HttpStatusCode.OK,
    ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
}

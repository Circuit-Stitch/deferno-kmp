package com.circuitstitch.deferno.core.data.auth

import com.circuitstitch.deferno.core.data.account.AccountBearerTokenProvider
import com.circuitstitch.deferno.core.data.account.DefaultAccountManager
import com.circuitstitch.deferno.core.data.account.DefaultReauthCoordinator
import com.circuitstitch.deferno.core.data.account.FakeAccountDataStore
import com.circuitstitch.deferno.core.data.account.InMemoryAccountRegistry
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.network.DefernoEnvironment
import com.circuitstitch.deferno.core.network.DefernoHttpClient
import com.circuitstitch.deferno.core.secure.InMemorySecretVault
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The live `/auth/me` tracer against the real staging backend (#20) — the truest proof the chain
 * works end to end over a genuine PAT, the real OkHttp engine, TLS, and staging's `0.1` envelope.
 *
 * **Absent-safe (skipped when blank).** It reads `deferno.staging.apiToken` / `deferno.staging.baseUrl`
 * from JVM system properties, which the build wires from the gitignored `local.properties`
 * (`core/data/build.gradle.kts`). When the token is blank — CI, and any dev without a staging PAT —
 * the test returns early so it never runs (no network) and never breaks the build. The token is the
 * user's own staging credential and is never committed (ADR-0009; CONTRACT-NOTES → "Staging-token wiring").
 *
 * `GET /auth/me` is read-only, so running it mutates nothing. It is a `jvmTest` (not commonTest)
 * because it makes a real network call through the JVM OkHttp engine.
 */
class StagingAuthMeIntegrationTest {

    @Test
    fun signedInAccountFetchesAuthMeFromTheLiveStagingBackend() {
        // assumeTrue (not an early return) so a tokenless run is reported as SKIPPED rather than a
        // vacuous PASS — CI and devs without a PAT see it skipped, honestly, and make no network call.
        val token = System.getProperty("deferno.staging.apiToken").orEmpty()
        assumeTrue(
            "deferno.staging.apiToken is blank — set it in local.properties to run the live /auth/me tracer (#20)",
            token.isNotBlank(),
        )

        // DefernoEnvironment is a closed set of baked-in https hosts (no free-form base URL). Select
        // the environment whose URL matches the configured staging base, defaulting to Staging.
        // Compare ignoring a trailing slash, since the enum base ends in `/api/` (load-bearing — see
        // DefernoEnvironment) while local.properties may record it without the slash.
        val baseUrl = System.getProperty("deferno.staging.baseUrl").orEmpty()
        val environment = DefernoEnvironment.entries.firstOrNull {
            it.baseUrl.trimEnd('/') == baseUrl.trimEnd('/')
        } ?: DefernoEnvironment.Staging

        runBlocking {
            val vault = InMemorySecretVault()
            val manager = DefaultAccountManager(InMemoryAccountRegistry(), vault, FakeAccountDataStore())
            manager.addAccount(Account(AccountId("staging"), "Staging"), token)
            val client = DefernoHttpClient(environment, AccountBearerTokenProvider(manager, vault))
            try {
                val repository = DefaultAuthRepository(
                    KtorAuthRemoteSource(client),
                    manager,
                    DefaultReauthCoordinator(),
                )

                val user = assertIs<MeResult.Authenticated>(repository.loadMe()).user

                assertTrue(user.username.isNotBlank(), "live /auth/me should return a username")
                assertTrue(
                    user.personalOrgId.value.isNotBlank(),
                    "live /auth/me should return personal_org_id (the Org isolation key, ADR-0002)",
                )
            } finally {
                client.close()
            }
        }
    }
}

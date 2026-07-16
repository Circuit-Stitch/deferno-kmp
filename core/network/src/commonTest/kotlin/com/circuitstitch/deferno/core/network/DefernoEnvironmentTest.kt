package com.circuitstitch.deferno.core.network

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The base-URL config (issue #17). Pins the verified URLs (spec `servers` + staging,
 * `contracts/CONTRACT-NOTES.md`) and the TLS-by-construction invariant: every non-local
 * environment is HTTPS, and the only cleartext URL is loopback.
 */
class DefernoEnvironmentTest {

    @Test
    fun baseUrlsMatchTheVerifiedContract() {
        // Trailing slash is required so appended paths keep the `/api` prefix (see DefernoEnvironment).
        assertEquals("https://app.defernowork.com/api/", DefernoEnvironment.Production.baseUrl)
        assertEquals("https://app2.defernowork.com/api/", DefernoEnvironment.Staging.baseUrl)
        assertEquals("http://localhost:3000/api/", DefernoEnvironment.Local.baseUrl)
    }

    @Test
    fun everyBaseUrlEndsWithATrailingSlashSoTheApiPrefixSurvivesPathAppending() {
        // The load-bearing invariant (#20): without the trailing slash, Ktor's relative resolution
        // drops `/api` when a request appends its path. Pinned here so no env can regress it.
        DefernoEnvironment.entries.forEach { env ->
            assertTrue(env.baseUrl.endsWith("/api/"), "${env.name} (${env.baseUrl}) must end with /api/")
        }
    }

    @Test
    fun fromNameResolvesEveryInjectedNameAndFailsSafeToProduction() {
        // fromName is the single home for the build-injected env string → enum mapping (ADR-0047):
        // Android's BuildConfig.DEFERNO_ENV and iOS's Info.plist DefernoEnv both flow through it. Each
        // enum's own name must round-trip to it — this doubles as a totality guard, so a new environment
        // added without a matching fromName arm fails here rather than silently falling back to Production.
        DefernoEnvironment.entries.forEach { env ->
            assertEquals(env, DefernoEnvironment.fromName(env.name), "fromName(${env.name}) must resolve to $env")
        }
        // Any unknown or absent value fails safe to Production (never throws); the match is case-sensitive.
        assertEquals(DefernoEnvironment.Production, DefernoEnvironment.fromName(null))
        assertEquals(DefernoEnvironment.Production, DefernoEnvironment.fromName(""))
        assertEquals(DefernoEnvironment.Production, DefernoEnvironment.fromName("Prod"))
        assertEquals(DefernoEnvironment.Production, DefernoEnvironment.fromName("staging"))
    }

    @Test
    fun everyRemoteEnvironmentIsHttps() {
        // Cleartext is allowed only for the loopback (local-dev) backend.
        DefernoEnvironment.entries.forEach { env ->
            val isHttps = env.baseUrl.startsWith("https://")
            val isLoopback = env.baseUrl.startsWith("http://localhost")
            assertTrue(isHttps || isLoopback, "${env.name} (${env.baseUrl}) is cleartext to a non-loopback host")
        }
    }
}

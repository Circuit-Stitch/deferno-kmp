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
        assertEquals("https://api.deferno.app/api", DefernoEnvironment.Production.baseUrl)
        assertEquals("https://app2.defernowork.com/api", DefernoEnvironment.Staging.baseUrl)
        assertEquals("http://localhost:3000/api", DefernoEnvironment.Local.baseUrl)
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

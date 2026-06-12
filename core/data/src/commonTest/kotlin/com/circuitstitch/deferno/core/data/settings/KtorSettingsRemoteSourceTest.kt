package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
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
 * Behaviour of [KtorSettingsRemoteSource] (#72) over Ktor's MockEngine (ADR-0006, no real network).
 * Proves it pulls `GET /auth/me/settings`, condenses the wire DTO to the domain [UserSettings], and
 * honours the offline-first contract: an error response yields `null` so a failed refresh leaves the
 * cached settings intact (ADR-0001).
 */
class KtorSettingsRemoteSourceTest {

    private val settingsEnvelope = """
        {"version":"0.1","data":{
            "theme_family":"mono","theme_mode":"dark","time_zone":"America/Los_Angeles",
            "global_done_visibility_seconds":259200,"dashboard_done_visibility_seconds":86400,
            "tracking_enabled":true,"drag_and_drop_enabled":true
        }}
    """.trimIndent()

    @Test
    fun fetchSettingsCondensesTheWireDtoToDomain() = runTest {
        var captured: HttpRequestData? = null
        val source = KtorSettingsRemoteSource(client { req -> captured = req; respondJson(settingsEnvelope) })

        val settings = (source.fetchSettings() as RemoteSnapshot.Available).value

        assertTrue(captured?.url?.encodedPath?.endsWith("/auth/me/settings") == true)
        assertEquals(ThemeFamily.Mono, settings.themeFamily)
        assertEquals(ThemeMode.Dark, settings.themeMode)
        assertEquals("America/Los_Angeles", settings.timeZone)
        assertEquals(259200L, settings.globalDoneVisibilitySeconds)
        assertEquals(true, settings.trackingEnabled)
        assertEquals(true, settings.dragAndDropEnabled)
    }

    @Test
    fun fetchSettingsReportsUnavailableOnFailureSoTheCacheStays() = runTest {
        val source = KtorSettingsRemoteSource(client { respond("", HttpStatusCode.Unauthorized) })

        assertEquals(RemoteSnapshot.Unavailable, source.fetchSettings())
    }

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

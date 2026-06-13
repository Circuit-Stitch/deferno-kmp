package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.network.DefernoEnvironment
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class InferenceEngineCatalogTest {

    private fun catalog(
        selected: InferenceEngineId = InferenceEngineId.Off,
        entitled: Boolean = false,
        credential: String? = "pat-123",
        baseUrl: String = DefernoEnvironment.Staging.baseUrl,
    ) = InferenceEngineCatalog.forRelay(
        baseUrl = baseUrl,
        preference = InMemoryInferenceEnginePreference(selected),
        entitlement = FakeRelayEntitlement(entitled),
        relayCredential = { credential },
    )

    // --- options: the cloud engine, disabled "Premium" until the Account is entitled (AC2) ---

    @Test
    fun optionsListTheCloudEngineDisabledUntilEntitled() = runTest {
        val disabled = catalog(entitled = false).options().single()
        assertEquals(InferenceEngineId.DefernoCloud, disabled.id)
        assertEquals(InferenceEngineOrigin.DefernoCloud, disabled.origin)
        assertEquals(InferenceEngineAvailability.RequiresPremium, disabled.availability)

        val enabled = catalog(entitled = true).options().single()
        assertEquals(InferenceEngineAvailability.Available, enabled.availability)
    }

    // --- the device-local choice: defaults to Off, round-trips through the preference ---

    @Test
    fun selectedDefaultsToOffAndRoundTrips() {
        val catalog = catalog()
        assertEquals(InferenceEngineId.Off, catalog.selected())
        catalog.select(InferenceEngineId.DefernoCloud)
        assertEquals(InferenceEngineId.DefernoCloud, catalog.selected())
    }

    @Test
    fun relayBaseUrlComesFromTheRegisteredEngine() {
        assertEquals(DefernoEnvironment.Staging.baseUrl, catalog().relayBaseUrl)
        // No engine registered → falls back to the Anthropic base rather than a blank URL.
        assertEquals(AnthropicEndpoint.ANTHROPIC_API_BASE_URL, InferenceEngineCatalog.Inert.relayBaseUrl)
    }

    @Test
    fun forEnvironmentUsesTheEnvironmentBaseUrl() {
        val catalog = InferenceEngineCatalog.forEnvironment(
            environment = DefernoEnvironment.Production,
            preference = InMemoryInferenceEnginePreference(),
            entitlement = FakeRelayEntitlement(entitled = false),
        )
        assertEquals(DefernoEnvironment.Production.baseUrl, catalog.relayBaseUrl)
    }

    // --- AC2: no credential (so the engine makes no network call) unless cloud is selected AND entitled ---

    @Test
    fun yieldsNoCredentialUnlessCloudSelectedAndEntitled() = runTest {
        assertNull(catalog(selected = InferenceEngineId.Off, entitled = true).credential())
        assertNull(catalog(selected = InferenceEngineId.DefernoCloud, entitled = false).credential())
        assertNull(catalog(selected = InferenceEngineId.Off, entitled = false).credential())
    }

    @Test
    fun yieldsTheRelayCredentialWhenCloudSelectedAndEntitled() = runTest {
        val open = catalog(selected = InferenceEngineId.DefernoCloud, entitled = true, credential = "pat-123")
        assertEquals("pat-123", open.credential())
    }

    // --- AC4: a changed selection or entitlement toggles the gate with no rebuild/restart ---

    @Test
    fun reReadsSelectionAndEntitlementPerCall() = runTest {
        val preference = InMemoryInferenceEnginePreference(InferenceEngineId.Off)
        val entitlement = FakeRelayEntitlement(entitled = false)
        val catalog = InferenceEngineCatalog.forRelay(
            baseUrl = "https://relay/",
            preference = preference,
            entitlement = entitlement,
            relayCredential = { "pat" },
        )

        assertNull(catalog.credential()) // Off + not entitled
        preference.setSelectedEngine(InferenceEngineId.DefernoCloud)
        assertNull(catalog.credential()) // cloud selected, still not entitled
        entitlement.entitled = true
        assertEquals("pat", catalog.credential()) // cloud + entitled → open
        entitlement.entitled = false
        assertNull(catalog.credential()) // entitlement revoked → shut, no restart
    }

    @Test
    fun inertCatalogHasNoOptionsAndIsShut() = runTest {
        assertTrue(InferenceEngineCatalog.Inert.options().isEmpty())
        assertEquals(InferenceEngineId.Off, InferenceEngineCatalog.Inert.selected())
        assertNull(InferenceEngineCatalog.Inert.credential())
    }
}

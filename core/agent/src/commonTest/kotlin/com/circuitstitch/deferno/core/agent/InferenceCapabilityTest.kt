package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class InferenceCapabilityTest {

    private fun catalog(
        entitled: Boolean = false,
        credential: InferenceCredentialProvider = InferenceCredentialProvider.Unconfigured,
    ) = InferenceEngineCatalog(
        engines = emptyList(),
        preference = InMemoryInferenceEnginePreference(),
        entitlement = RelayEntitlement { entitled },
        relayCredential = credential,
    )

    @Test
    fun off_has_no_general_purpose_engine() = runTest {
        assertFalse(catalog().hasGeneralPurposeEngine()) // default selection is Off
    }

    @Test
    fun the_brain_dump_floor_is_not_general_purpose() = runTest {
        val c = catalog().also { it.select(InferenceEngineId.OnDeviceFloor) }
        assertFalse(c.hasGeneralPurposeEngine())
    }

    @Test
    fun an_entitled_reachable_cloud_engine_is_general_purpose() = runTest {
        val c = catalog(entitled = true, credential = InferenceCredentialProvider { "tok" })
            .also { it.select(InferenceEngineId.DefernoCloud) }
        assertTrue(c.hasGeneralPurposeEngine())
    }

    @Test
    fun cloud_without_a_reachable_credential_is_not_general_purpose() = runTest {
        val c = catalog(entitled = true) // credential Unconfigured → null
            .also { it.select(InferenceEngineId.DefernoCloud) }
        assertFalse(c.hasGeneralPurposeEngine())
    }

    @Test
    fun an_unentitled_cloud_engine_is_not_general_purpose() = runTest {
        val c = catalog(entitled = false, credential = InferenceCredentialProvider { "tok" })
            .also { it.select(InferenceEngineId.DefernoCloud) }
        assertFalse(c.hasGeneralPurposeEngine())
    }

    @Test
    fun a_general_purpose_on_device_engine_counts() = runTest {
        val c = catalog().also { it.select(InferenceEngineId.OnDeviceFoundationModels) }
        assertTrue(c.hasGeneralPurposeEngine())
    }
}

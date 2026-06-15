package com.circuitstitch.deferno.core.agent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class RoutingInferenceEngineTest {
    private val schema = InferenceSchema(DraftTasks.serializer())
    private fun request() = InferenceRequest("instructions", "content", schema)

    private fun catalog(selected: InferenceEngineId) = InferenceEngineCatalog.forRelay(
        baseUrl = "https://relay.example",
        preference = InMemoryInferenceEnginePreference(selected),
        entitlement = RelayEntitlement { true },
    )

    private fun engine(title: String) =
        FakeInferenceEngine().apply { enqueue(DraftTasks(listOf(DraftTask(id = title, title = title)))) }

    @Test
    fun routes_to_the_selected_engine_only() = runTest {
        val cloud = engine("Cloud")
        val floor = engine("Floor")
        val router = RoutingInferenceEngine(
            mapOf(InferenceEngineId.DefernoCloud to cloud, InferenceEngineId.OnDeviceFloor to floor),
            catalog(InferenceEngineId.OnDeviceFloor),
        )

        val result = assertIs<InferenceResult.Success<DraftTasks>>(router.infer(request()))
        assertEquals("Floor", result.value.drafts.single().title)
        assertEquals(0, cloud.requests.size) // the unselected engine is never touched
    }

    @Test
    fun off_routes_to_not_configured() = runTest {
        val router = RoutingInferenceEngine(
            mapOf(InferenceEngineId.DefernoCloud to engine("Cloud")),
            catalog(InferenceEngineId.Off),
        )
        assertIs<InferenceResult.Failure.NotConfigured>(router.infer(request()))
    }

    @Test
    fun a_selection_with_no_registered_engine_is_not_configured() = runTest {
        // Floor selected on a device where no floor engine is registered (e.g. JVM) → no engine, no crash.
        val router = RoutingInferenceEngine(
            mapOf(InferenceEngineId.DefernoCloud to engine("Cloud")),
            catalog(InferenceEngineId.OnDeviceFloor),
        )
        assertIs<InferenceResult.Failure.NotConfigured>(router.infer(request()))
    }
}

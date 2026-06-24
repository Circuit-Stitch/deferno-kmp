package com.circuitstitch.deferno.core.common

import com.arkivanov.decompose.value.MutableValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Value→StateFlow seam SKIE consumes on iOS/macOS — seed, transform, live update, scope teardown. */
class ValueAsStateFlowTest {
    @Test
    fun seedsTransformsAndTracksThenStopsOnScopeCancel() {
        val source = MutableValue(1)
        val scope = CoroutineScope(Job())
        val flow = source.asStateFlow(scope) { it * 10 }

        assertEquals(10, flow.value) // synchronous seed for the first SwiftUI render

        source.value = 2
        assertEquals(20, flow.value) // tracks live emissions through the transform

        scope.cancel() // invokeOnCompletion cancels the Value.subscribe synchronously
        source.value = 3
        assertEquals(20, flow.value) // no longer tracking after teardown
    }
}

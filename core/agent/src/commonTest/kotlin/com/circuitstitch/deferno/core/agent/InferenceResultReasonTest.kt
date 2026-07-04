package com.circuitstitch.deferno.core.agent

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Each [InferenceResult.Failure] carries a typed [InferenceResult.Failure.reason] so a UI can render a
 * localized message, while [InferenceResult.Failure.detail] stays a content-free log string (#327). The
 * three failure subtypes map 1:1 to the three reasons.
 */
class InferenceResultReasonTest {

    @Test
    fun notConfiguredCarriesTheNotConfiguredReason() {
        val failure: InferenceResult.Failure = InferenceResult.Failure.NotConfigured("no credential")
        assertEquals(InferenceFailureReason.NotConfigured, failure.reason)
        assertEquals("no credential", failure.detail)
    }

    @Test
    fun malformedOutputCarriesTheMalformedOutputReason() {
        val failure: InferenceResult.Failure = InferenceResult.Failure.MalformedOutput("bad json")
        assertEquals(InferenceFailureReason.MalformedOutput, failure.reason)
    }

    @Test
    fun transportCarriesTheTransportReason() {
        val failure: InferenceResult.Failure = InferenceResult.Failure.Transport("503")
        assertEquals(InferenceFailureReason.Transport, failure.reason)
    }
}

package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Enforces the #118 acceptance criterion / ADR-0009 invariant: **no [[Transcript]] text is logged**. The
 * transport can't know which payloads are privacy-critical, so every payload-bearing surface
 * ([SidecarFrame], [TranscriptWire], [SidecarPush], [SidecarError], [SidecarException]) must redact its
 * payload from `toString`/messages — only metadata (id, method, topic, error code) may appear. These
 * assertions fail loudly if anyone "improves" a `toString` to dump the body.
 */
class NoTranscriptLoggingTest {

    private val secret = "PRIVATE-DICTATED-SENTENCE"

    @Test
    fun transcriptWireRedactsItsText() {
        assertFalse(secret in TranscriptWire.Partial(secret).toString())
        assertFalse(secret in TranscriptWire.Final(secret).toString())
    }

    @Test
    fun streamDataFrameRedactsItsEvent() {
        val frame = SidecarFrame.StreamData(id = 1, event = JsonPrimitive(secret))
        assertFalse(secret in frame.toString())
        assertTrue("StreamData" in frame.toString()) // metadata still useful
    }

    @Test
    fun pushFrameAndPushValueRedactTheirPayload() {
        val payload = buildJsonObject { put("text", JsonPrimitive(secret)) }
        assertFalse(secret in SidecarFrame.Push(topic = "t", payload = payload).toString())
        assertFalse(secret in SidecarPush(topic = "t", payload = payload).toString())
    }

    @Test
    fun requestAndResponseFramesRedactTheirPayload() {
        val params = buildJsonObject { put("text", JsonPrimitive(secret)) }
        assertFalse(secret in SidecarFrame.Request(id = 1, method = "m", params = params).toString())
        assertFalse(secret in SidecarFrame.Response(id = 1, result = JsonPrimitive(secret)).toString())
    }

    @Test
    fun errorAndRequestExceptionExposeOnlyMetadata() {
        val error = SidecarError(
            code = SidecarErrorCode.INTERNAL,
            message = "engine failed", // a non-PII summary, by contract
            details = JsonPrimitive(secret),
        )
        assertFalse(secret in error.toString())
        assertFalse(secret in SidecarRequestException(error).message.orEmpty())
        assertTrue("INTERNAL" in SidecarRequestException(error).message.orEmpty())
    }
}

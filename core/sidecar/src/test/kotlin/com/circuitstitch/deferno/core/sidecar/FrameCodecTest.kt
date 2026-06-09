package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.ByteBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class FrameCodecTest {

    @Test
    fun roundTripsEachFrameKind() = runBlocking {
        val frames = listOf(
            SidecarFrame.Hello(token = "t0ken", protocolVersion = 1),
            SidecarFrame.Welcome(protocolVersion = 1, capabilities = setOf("permissions", "speech.transcribe")),
            SidecarFrame.Request(id = 7, method = "queryPermission", params = buildJsonObject { put("capability", JsonPrimitive("mic")) }),
            SidecarFrame.Response(id = 7, result = JsonPrimitive("ok")),
            SidecarFrame.StreamData(id = 9, event = JsonPrimitive("partial")),
            SidecarFrame.StreamEnd(id = 9),
            SidecarFrame.Cancel(id = 9),
            SidecarFrame.Push(topic = "permissionChanged", payload = JsonPrimitive("denied")),
            SidecarFrame.Failure(id = 7, error = SidecarError(SidecarErrorCode.INVALID_PARAMS, "bad")),
            SidecarFrame.Failure(id = null, error = SidecarError(SidecarErrorCode.UNAUTHENTICATED, "no")),
        )
        for (frame in frames) {
            assertEquals(frame, roundTrip(frame), "frame did not survive a codec round-trip: $frame")
        }
    }

    @Test
    fun reassemblesAcrossShortReads() = runBlocking {
        val frame = SidecarFrame.Response(id = 42, result = JsonPrimitive("payload-spanning-multiple-reads"))
        val bytes = encode(frame)
        // One byte per read forces the fill-loop to reassemble both the length prefix and the body.
        val decoded = FrameCodec(SourceConnection(bytes, chunkSize = 1)).readFrame()
        assertEquals(frame, decoded)
    }

    @Test
    fun returnsNullOnCleanEndOfStreamAtBoundary() = runBlocking {
        assertNull(FrameCodec(SourceConnection(ByteArray(0))).readFrame())
    }

    @Test
    fun throwsOnFrameTruncatedInTheLengthPrefix() = runBlocking {
        val bytes = encode(SidecarFrame.StreamEnd(id = 1)).copyOf(2) // half a length prefix
        assertFailsWith<SidecarProtocolException> { FrameCodec(SourceConnection(bytes)).readFrame() }
        Unit
    }

    @Test
    fun throwsOnFrameTruncatedInTheBody() = runBlocking {
        val full = encode(SidecarFrame.Response(id = 1, result = JsonPrimitive("abc")))
        val bytes = full.copyOf(full.size - 1) // body missing its last byte
        assertFailsWith<SidecarProtocolException> { FrameCodec(SourceConnection(bytes)).readFrame() }
        Unit
    }

    @Test
    fun rejectsAnOversizeInboundLength() = runBlocking {
        val prefix = ByteBuffer.allocate(FrameCodec.LENGTH_PREFIX_BYTES)
            .putInt(FrameCodec.DEFAULT_MAX_FRAME_BYTES + 1)
            .array()
        assertFailsWith<SidecarProtocolException> { FrameCodec(SourceConnection(prefix)).readFrame() }
        Unit
    }

    @Test
    fun rejectsAnOversizeOutboundFrame() = runBlocking {
        val codec = FrameCodec(SinkConnection(), maxFrameBytes = 8)
        assertFailsWith<SidecarProtocolException> {
            codec.writeFrame(SidecarFrame.Response(id = 1, result = JsonPrimitive("this body is well over eight bytes")))
        }
        Unit
    }

    private suspend fun roundTrip(frame: SidecarFrame): SidecarFrame? =
        FrameCodec(SourceConnection(encode(frame))).readFrame()

    private suspend fun encode(frame: SidecarFrame): ByteArray {
        val sink = SinkConnection()
        FrameCodec(sink).writeFrame(frame)
        return sink.bytes()
    }
}

package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.SerializationException
import java.nio.ByteBuffer
import java.nio.channels.ClosedChannelException

/**
 * The Sidecar wire framing (ADR-0024): each [SidecarFrame] is a **4-byte big-endian length prefix +
 * UTF-8 JSON** body. Length-prefixing (over newline-delimited JSON) is the robust, language-neutral
 * choice for a contract a Swift/C#/Rust Helper must also implement — unambiguous against partial reads
 * and binary-clean. A [maxFrameBytes] cap bounds memory: an over-cap length (inbound or outbound) is a
 * [SidecarProtocolException], never an allocation.
 *
 * Operates purely over a [Connection] byte stream — it never sees the concrete transport, so it is
 * identical for AF_UNIX today and a named pipe later (ADR-0025).
 */
internal class FrameCodec(
    private val connection: Connection,
    private val maxFrameBytes: Int = DEFAULT_MAX_FRAME_BYTES,
) {
    suspend fun writeFrame(frame: SidecarFrame) {
        val body = SidecarJson.encodeToString(SidecarFrame.serializer(), frame).encodeToByteArray()
        if (body.size > maxFrameBytes) {
            throw SidecarProtocolException("outbound ${frame::class.simpleName} too large: ${body.size} > $maxFrameBytes")
        }
        val buffer = ByteBuffer.allocate(LENGTH_PREFIX_BYTES + body.size)
        buffer.putInt(body.size) // big-endian (ByteBuffer default)
        buffer.put(body)
        buffer.flip()
        connection.write(buffer)
    }

    /** Read exactly one frame, suspending. Returns `null` at a **clean** end-of-stream (peer closed). */
    suspend fun readFrame(): SidecarFrame? {
        val header = readFully(LENGTH_PREFIX_BYTES, atFrameBoundary = true) ?: return null
        val length = header.getInt(0)
        if (length <= 0 || length > maxFrameBytes) {
            throw SidecarProtocolException("inbound frame length out of range: $length")
        }
        val body = readFully(length, atFrameBoundary = false)
            ?: throw SidecarProtocolException("stream ended mid-frame")
        return try {
            SidecarJson.decodeFromString(SidecarFrame.serializer(), body.array().decodeToString())
        } catch (e: SerializationException) {
            // Never echo the body — it may carry Transcript text (ADR-0009).
            throw SidecarProtocolException("malformed inbound frame", e)
        }
    }

    /**
     * Fill a buffer of exactly [n] bytes. Returns `null` only when the stream ends with **no** bytes read
     * at a frame boundary ([atFrameBoundary] — a clean close between frames); an end-of-stream partway
     * through is a truncated frame and surfaces as a [SidecarProtocolException] to the caller.
     */
    private suspend fun readFully(n: Int, atFrameBoundary: Boolean): ByteBuffer? {
        val buffer = ByteBuffer.allocate(n)
        while (buffer.hasRemaining()) {
            val read = try {
                connection.read(buffer)
            } catch (e: ClosedChannelException) {
                // Closed mid-read: clean boundary → treat as end-of-stream; otherwise truncation.
                if (atFrameBoundary && buffer.position() == 0) return null else throw e
            }
            if (read < 0) {
                if (atFrameBoundary && buffer.position() == 0) return null
                throw SidecarProtocolException("stream ended mid-frame")
            }
        }
        buffer.flip()
        return buffer
    }

    companion object {
        const val LENGTH_PREFIX_BYTES = 4

        /** 1 MiB — generous for a control frame or a Transcript chunk, tight enough to bound memory. */
        const val DEFAULT_MAX_FRAME_BYTES = 1 * 1024 * 1024
    }
}

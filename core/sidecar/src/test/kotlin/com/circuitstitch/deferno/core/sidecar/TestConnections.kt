package com.circuitstitch.deferno.core.sidecar

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/**
 * In-memory [Connection]s for codec unit tests — no socket, no threads. [SinkConnection] captures
 * everything written; [SourceConnection] replays a fixed byte sequence and reports end-of-stream,
 * exercising [FrameCodec] against partial reads and truncation deterministically.
 */
internal class SinkConnection : Connection {
    private val sink = ByteArrayOutputStream()

    fun bytes(): ByteArray = sink.toByteArray()

    override suspend fun read(dst: ByteBuffer): Int = -1
    override suspend fun write(src: ByteBuffer) {
        val chunk = ByteArray(src.remaining())
        src.get(chunk)
        sink.write(chunk)
    }

    override fun close() = Unit
}

/**
 * Replays [bytes], handing out at most [chunkSize] bytes per [read] so the codec's fill-loop is
 * exercised (a real socket also returns short reads), then `-1` at end-of-stream.
 */
internal class SourceConnection(
    bytes: ByteArray,
    private val chunkSize: Int = Int.MAX_VALUE,
) : Connection {
    private val buffer = ByteBuffer.wrap(bytes)

    override suspend fun read(dst: ByteBuffer): Int {
        if (!buffer.hasRemaining()) return -1
        var moved = 0
        val limit = minOf(dst.remaining(), chunkSize)
        while (buffer.hasRemaining() && moved < limit) {
            dst.put(buffer.get())
            moved++
        }
        return moved
    }

    override suspend fun write(src: ByteBuffer) = Unit
    override fun close() = Unit
}

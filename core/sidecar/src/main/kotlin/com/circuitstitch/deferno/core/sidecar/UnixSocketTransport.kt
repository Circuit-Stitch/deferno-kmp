package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path

/**
 * The AF_UNIX [Transport] (ADR-0024/0025) — JDK 17's native Unix-domain socket support (JEP 380), so no
 * JNI. One transport serves macOS, Linux, **and** Windows 10 1803+; a Windows-native `NamedPipeTransport`
 * is a planned sibling, not a prerequisite (ADR-0025).
 *
 * On [connect] it runs the client-half [peerTrust] check **before** dialing, then opens a blocking
 * [SocketChannel]; all IO is moved to [ioDispatcher]. An absent/stale path or a refused connection
 * surfaces as [SidecarUnavailableException] so callers degrade gracefully (#118).
 *
 * @param path the well-known socket path the Helper binds (configurable; see [SidecarSocketPath]).
 */
class UnixSocketTransport(
    private val path: Path,
    private val peerTrust: PeerTrust = PosixPeerTrust(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : Transport {

    override suspend fun connect(): Connection = withContext(ioDispatcher) {
        // Connection-absent: no socket at the path (no Helper, or a cleaned-up one) → degrade, don't fail.
        if (Files.notExists(path)) {
            throw SidecarUnavailableException("no Sidecar Helper socket at $path")
        }
        // Client-half peer-auth: the path must be ours and owner-only before we speak on it (ADR-0009).
        peerTrust.verify(path)

        val channel = SocketChannel.open(StandardProtocolFamily.UNIX)
        try {
            channel.connect(UnixDomainSocketAddress.of(path))
        } catch (e: IOException) {
            channel.closeQuietly()
            // A bound-but-unaccepting or torn-down endpoint reads as absent — the caller degrades.
            throw SidecarUnavailableException("could not connect to Sidecar Helper at $path", e)
        }
        ChannelConnection(channel, ioDispatcher)
    }
}

/** Adapts a blocking [SocketChannel] to the transport-neutral [Connection] byte stream. */
private class ChannelConnection(
    private val channel: SocketChannel,
    private val ioDispatcher: CoroutineDispatcher,
) : Connection {

    override suspend fun read(dst: ByteBuffer): Int = withContext(ioDispatcher) { channel.read(dst) }

    override suspend fun write(src: ByteBuffer): Unit = withContext(ioDispatcher) {
        while (src.hasRemaining()) {
            channel.write(src)
        }
    }

    override fun close() = channel.closeQuietly()
}

/** Close, swallowing the close-time IOException — there is nothing useful to do with it. */
internal fun SocketChannel.closeQuietly() {
    try {
        close()
    } catch (_: IOException) {
        // ignore
    }
}

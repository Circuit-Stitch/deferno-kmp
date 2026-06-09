package com.circuitstitch.deferno.core.sidecar

import java.nio.ByteBuffer

/**
 * How the Sidecar client reaches a bound Helper — the **first-class, OS-pluggable** seam of ADR-0025.
 * #118 ships only [UnixSocketTransport] (AF_UNIX, which serves macOS, Linux, and Windows 10 1803+); a
 * Windows-native `NamedPipeTransport` is a planned drop-in that lands with the Windows Helper. Because
 * the codec and client sit entirely above [Connection] (a plain byte stream — see below), adding a new
 * transport touches **zero** existing code.
 *
 * Peer-auth is deliberately **not** part of this seam: it is OS-specific (POSIX owner+0600 vs Windows
 * ACL) and lives in a separate `PeerTrust` so no transport has to weaken its check to a portable
 * lowest-common-denominator (ADR-0025). Each transport runs its own trust check before handing back a
 * [Connection].
 */
interface Transport {
    /**
     * Open a connection to the bound Helper, after running this transport's peer-auth trust check.
     *
     * @throws SidecarUnavailableException if no Helper is bound (path absent/stale, connection refused) —
     *   the caller degrades gracefully.
     * @throws SidecarSecurityException if the endpoint fails the client-half trust check.
     */
    suspend fun connect(): Connection
}

/**
 * A live, bidirectional **byte stream** to a Helper — the transport-neutral boundary the [FrameCodec]
 * reads and writes. Deliberately **not** a `SocketChannel`: a named pipe (the planned Windows transport)
 * cannot produce a `SocketChannel` but can satisfy this from its streams, so keeping the boundary at raw
 * bytes is exactly what makes a new transport invisible to the codec/client (ADR-0025). All operations
 * suspend; implementations move blocking IO off the caller's dispatcher.
 */
interface Connection {
    /**
     * Read available bytes into [dst], suspending until at least one is read. Returns the count, or `-1`
     * at end-of-stream (the peer closed). May read fewer than [dst] has room for — the codec loops.
     */
    suspend fun read(dst: ByteBuffer): Int

    /** Write **all** of [src]'s remaining bytes, suspending until done. */
    suspend fun write(src: ByteBuffer)

    /** Close the connection; idempotent. A blocked [read]/[write] fails. */
    fun close()
}

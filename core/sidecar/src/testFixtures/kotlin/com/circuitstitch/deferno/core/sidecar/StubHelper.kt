package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import java.io.IOException
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.ConcurrentHashMap

/**
 * The **Linux stub Helper** (#118): a real AF_UNIX server that binds the same socket and speaks the
 * Sidecar [[Sidecar protocol]] with **canned** responses, so the entire JVM client path runs end-to-end
 * on the Linux fast path with no Mac (ADR-0024). It is the *reference implementation of the server half*
 * — every native Helper (Swift/Windows/Linux) must behave the same against the client.
 *
 * It deliberately reuses the production [FrameCodec] + [SidecarJson] so the wire format can't drift from
 * the client's; independent framing correctness is checked separately (FrameCodecTest + the golden
 * fixtures). Canned behavior:
 * - handshake: validates the in-band token → [SidecarFrame.Welcome] + [capabilities]; wrong token →
 *   connection-level [SidecarFrame.Failure] (`UNAUTHENTICATED`) then close;
 * - [SidecarMethods.QueryPermission] → a [SidecarFrame.Response] echoing the queried capability
 *   (default speech) **and** a follow-on [SidecarTopics.PermissionChanged] [SidecarFrame.Push] (so a
 *   collector can observe an unsolicited push);
 * - [SidecarMethods.SubscribeTranscript] → a [SidecarFrame.StreamData] sequence (Partial…/Final) then
 *   [SidecarFrame.StreamEnd], with delays so a collector can cancel mid-stream;
 * - [SidecarMethods.PostNotification] → an empty-ack [SidecarFrame.Response], recorded for
 *   [awaitNotification]; an empty/missing title → `INVALID_PARAMS`, an un-[GRANTED][permissionStatus]
 *   state → `UNAVAILABLE` (`notification-permission-denied`), mirroring the real Helper (#123);
 * - [SidecarMethods.SetStatusItem] → an empty-ack [SidecarFrame.Response]; showing it additionally
 *   pushes one [SidecarTopics.StatusItemClicked] (a canned "click", so the push path is observable
 *   without a real menu bar — #125);
 * - [SidecarMethods.RegisterHotkey] → an empty-ack [SidecarFrame.Response] then one
 *   [SidecarTopics.HotkeyFired] push (a canned "fire"); a key outside [SidecarHotkeyKeys] or an empty
 *   modifier set → `INVALID_PARAMS`. [SidecarMethods.UnregisterHotkey] → an idempotent empty ack,
 *   recorded for [awaitUnregister];
 * - [SidecarFrame.Cancel] → stops that stream and is recorded for [awaitCancel];
 * - any other method → a [SidecarFrame.Failure] (`UNKNOWN_METHOD`).
 */
class StubHelper(
    val path: Path,
    private val expectedToken: String,
    private val capabilities: Set<String> = DEFAULT_CAPABILITIES,
) : AutoCloseable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val server: ServerSocketChannel = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
    private val cancelledStreamIds = Channel<Long>(Channel.BUFFERED)
    private val postedNotifications = Channel<PostNotificationWire>(Channel.BUFFERED)
    private val unregisteredHotkeyIds = Channel<Long>(Channel.BUFFERED)

    // Accepted peer sockets, tracked so [close] can shut them explicitly: coroutine cancellation does
    // NOT interrupt a thread blocked in SocketChannel.read, so closing the channel is what unblocks the
    // serve loop AND sends EOF to the client (simulating a Helper drop, exercising the client's reconnect).
    private val connections = ConcurrentHashMap.newKeySet<SocketChannel>()

    /** The canned permission state the stub reports / pushes (the [SidecarMethods.QueryPermission] result). */
    var permissionStatus: PermissionStatusValue = PermissionStatusValue.GRANTED

    /** Bind the socket and start accepting. The socket is chmod'd `0600` so the client's PosixPeerTrust passes. */
    fun start() {
        Files.deleteIfExists(path)
        server.bind(UnixDomainSocketAddress.of(path))
        // Model what launchd / the real Helper must do: make the socket owner-only (ADR-0009).
        runCatching { Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------")) }
        scope.launch { acceptLoop() }
    }

    /** Suspend until the stub receives a [SidecarFrame.Cancel], returning the cancelled stream id. */
    suspend fun awaitCancel(): Long = cancelledStreamIds.receive()

    /** Suspend until the stub accepts a [SidecarMethods.PostNotification], returning what was posted. */
    suspend fun awaitNotification(): PostNotificationWire = postedNotifications.receive()

    /** Suspend until the stub receives a [SidecarMethods.UnregisterHotkey], returning its hotkey id. */
    suspend fun awaitUnregister(): Long = unregisteredHotkeyIds.receive()

    private suspend fun acceptLoop() {
        while (true) {
            val socket = try {
                withContext(Dispatchers.IO) { server.accept() }
            } catch (_: IOException) {
                break // server closed
            }
            connections.add(socket)
            scope.launch { serve(socket) }
        }
    }

    private suspend fun serve(socket: SocketChannel) {
        val connection = SocketChannelConnection(socket)
        val codec = FrameCodec(connection)
        val out = Outbound(codec)
        val streamJobs = ConcurrentHashMap<Long, Job>()
        try {
            // Handshake.
            val hello = codec.readFrame()
            if (hello !is SidecarFrame.Hello || hello.token != expectedToken) {
                out.send(
                    SidecarFrame.Failure(
                        id = null,
                        error = SidecarError(SidecarErrorCode.UNAUTHENTICATED, "invalid sidecar token"),
                    ),
                )
                return
            }
            out.send(SidecarFrame.Welcome(SIDECAR_PROTOCOL_VERSION, capabilities))

            // Serve loop.
            while (true) {
                val frame = codec.readFrame() ?: break
                when (frame) {
                    is SidecarFrame.Request -> when (frame.method) {
                        SidecarMethods.QueryPermission -> {
                            // Echo the queried capability (absent params default to speech, per the
                            // contract) so a notifications/mic query reads back as itself.
                            val capability = frame.params?.let { params ->
                                runCatching {
                                    SidecarJson.decodeFromJsonElement(QueryPermissionWire.serializer(), params).capability
                                }.getOrNull()
                            } ?: SidecarPermissionCapabilities.Speech
                            out.send(SidecarFrame.Response(frame.id, permissionElement(capability)))
                            out.send(SidecarFrame.Push(SidecarTopics.PermissionChanged, permissionElement(capability)))
                        }

                        SidecarMethods.SubscribeTranscript ->
                            streamJobs[frame.id] = scope.launch { emitTranscript(out, frame.id) }

                        SidecarMethods.PostNotification -> {
                            val notification = frame.params?.let { params ->
                                runCatching {
                                    SidecarJson.decodeFromJsonElement(PostNotificationWire.serializer(), params)
                                }.getOrNull()
                            }
                            when {
                                notification == null || notification.title.isEmpty() -> out.send(
                                    SidecarFrame.Failure(
                                        frame.id,
                                        SidecarError(
                                            SidecarErrorCode.INVALID_PARAMS,
                                            "postNotification requires a non-empty title",
                                        ),
                                    ),
                                )

                                permissionStatus != PermissionStatusValue.GRANTED -> out.send(
                                    SidecarFrame.Failure(
                                        frame.id,
                                        SidecarError(SidecarErrorCode.UNAVAILABLE, "notification-permission-denied"),
                                    ),
                                )

                                else -> {
                                    postedNotifications.trySend(notification)
                                    out.send(SidecarFrame.Response(frame.id))
                                }
                            }
                        }

                        SidecarMethods.SetStatusItem -> {
                            val wire = frame.params?.let { params ->
                                runCatching {
                                    SidecarJson.decodeFromJsonElement(SetStatusItemWire.serializer(), params)
                                }.getOrNull()
                            }
                            if (wire == null) {
                                out.send(
                                    SidecarFrame.Failure(
                                        frame.id,
                                        SidecarError(SidecarErrorCode.INVALID_PARAMS, "setStatusItem requires visible"),
                                    ),
                                )
                            } else {
                                out.send(SidecarFrame.Response(frame.id))
                                // A canned "click" right after showing, so the push path is observable
                                // without a real menu bar (the analogue of queryPermission's push).
                                if (wire.visible) {
                                    out.send(SidecarFrame.Push(SidecarTopics.StatusItemClicked, JsonObject(emptyMap())))
                                }
                            }
                        }

                        SidecarMethods.RegisterHotkey -> {
                            val wire = frame.params?.let { params ->
                                runCatching {
                                    SidecarJson.decodeFromJsonElement(RegisterHotkeyWire.serializer(), params)
                                }.getOrNull()
                            }
                            if (wire == null || wire.key !in SidecarHotkeyKeys.All || wire.modifiers.isEmpty()) {
                                out.send(
                                    SidecarFrame.Failure(
                                        frame.id,
                                        SidecarError(
                                            SidecarErrorCode.INVALID_PARAMS,
                                            "registerHotkey requires a known key and a non-empty modifier set",
                                        ),
                                    ),
                                )
                            } else {
                                out.send(SidecarFrame.Response(frame.id))
                                // A canned "fire" right after registering — same observability trick.
                                out.send(
                                    SidecarFrame.Push(
                                        SidecarTopics.HotkeyFired,
                                        SidecarJson.encodeToJsonElement(
                                            HotkeyFiredWire.serializer(),
                                            HotkeyFiredWire(wire.id),
                                        ),
                                    ),
                                )
                            }
                        }

                        SidecarMethods.UnregisterHotkey -> {
                            val wire = frame.params?.let { params ->
                                runCatching {
                                    SidecarJson.decodeFromJsonElement(UnregisterHotkeyWire.serializer(), params)
                                }.getOrNull()
                            }
                            if (wire == null) {
                                out.send(
                                    SidecarFrame.Failure(
                                        frame.id,
                                        SidecarError(SidecarErrorCode.INVALID_PARAMS, "unregisterHotkey requires an id"),
                                    ),
                                )
                            } else {
                                unregisteredHotkeyIds.trySend(wire.id)
                                out.send(SidecarFrame.Response(frame.id)) // idempotent ack (unknown ids too)
                            }
                        }

                        else -> out.send(
                            SidecarFrame.Failure(
                                frame.id,
                                SidecarError(SidecarErrorCode.UNKNOWN_METHOD, "no such method: ${frame.method}"),
                            ),
                        )
                    }

                    is SidecarFrame.Cancel -> {
                        streamJobs.remove(frame.id)?.cancel()
                        cancelledStreamIds.trySend(frame.id)
                    }

                    else -> Unit // a client should not send other frame kinds
                }
            }
        } catch (_: Throwable) {
            // connection closed / torn down — end this serve coroutine
        } finally {
            streamJobs.values.forEach { it.cancel() }
            connections.remove(socket)
            connection.close()
        }
    }

    private suspend fun emitTranscript(out: Outbound, id: Long) {
        out.send(SidecarFrame.StreamData(id, transcriptElement(TranscriptWire.Partial("hel"))))
        delay(STREAM_GAP_MILLIS)
        out.send(SidecarFrame.StreamData(id, transcriptElement(TranscriptWire.Partial("hello wor"))))
        delay(STREAM_GAP_MILLIS)
        out.send(SidecarFrame.StreamData(id, transcriptElement(TranscriptWire.Final("hello world"))))
        out.send(SidecarFrame.StreamEnd(id))
    }

    private fun permissionElement(capability: String = SidecarPermissionCapabilities.Speech): JsonElement =
        SidecarJson.encodeToJsonElement(
            PermissionStatusWire.serializer(),
            PermissionStatusWire(capability, permissionStatus),
        )

    private fun transcriptElement(event: TranscriptWire): JsonElement =
        SidecarJson.encodeToJsonElement(TranscriptWire.serializer(), event)

    override fun close() {
        runCatching { server.close() }
        // Close accepted peers explicitly — this is what unblocks the serve reads and signals EOF to the
        // client (cancelling the scope alone won't interrupt a blocking SocketChannel.read).
        connections.forEach { runCatching { it.close() } }
        scope.cancel()
        runCatching { Files.deleteIfExists(path) }
    }

    /** Serialises all outbound frames on one connection (the serve loop + concurrent stream emitters). */
    private class Outbound(private val codec: FrameCodec) {
        private val mutex = Mutex()
        suspend fun send(frame: SidecarFrame) = mutex.withLock { codec.writeFrame(frame) }
    }

    /** Server-side [Connection] over an accepted [SocketChannel] (mirrors the client's channel adapter). */
    private class SocketChannelConnection(private val channel: SocketChannel) : Connection {
        override suspend fun read(dst: ByteBuffer): Int = withContext(Dispatchers.IO) { channel.read(dst) }
        override suspend fun write(src: ByteBuffer): Unit = withContext(Dispatchers.IO) {
            while (src.hasRemaining()) channel.write(src)
        }

        override fun close() {
            runCatching { channel.close() }
        }
    }

    companion object {
        /** Everything the full reference Helper advertises (#118/#123/#125) — the constructor default. */
        val DEFAULT_CAPABILITIES: Set<String> = setOf(
            SidecarCapabilities.Permissions,
            SidecarCapabilities.SpeechTranscribe,
            SidecarCapabilities.Notifications,
            SidecarCapabilities.StatusItem,
            SidecarCapabilities.Hotkeys,
        )

        private const val STREAM_GAP_MILLIS = 50L
    }
}

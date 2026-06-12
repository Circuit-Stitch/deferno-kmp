package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonElement
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * The default multiplexing [SidecarClient] (ADR-0024). One [Transport] connection carries every traffic
 * shape: a single **reader** coroutine demultiplexes inbound frames to the right waiter — a
 * [CompletableDeferred] per in-flight [request], a [Channel] per open [openStream], and a [SharedFlow]
 * for [pushes] — while a [writeMutex] serialises outbound frames. Correlation ids come from one
 * [AtomicLong].
 *
 * Lost connections are handled modestly (the #118 tracer-bullet scope): the reader fails all in-flight
 * requests and open streams with [SidecarConnectionLostException], and the next [request]/[openStream]
 * **re-dials** (the "reconnect" of the issue). Per-connection state lives in a [Session] so a stale
 * connection's teardown can never corrupt a freshly re-dialed one.
 */
class DefaultSidecarClient(
    private val transport: Transport,
    /**
     * Resolved **per handshake**, not at construction: the client re-dials whenever a Helper (re)binds
     * the socket, and a token provisioned after startup (the #122 first-run LaunchAgent install) must
     * authenticate on that re-dial without an app restart.
     */
    private val token: () -> String,
    private val protocolVersion: Int = SIDECAR_PROTOCOL_VERSION,
    private val handshakeTimeoutMillis: Long = 5_000,
    parentScope: CoroutineScope? = null,
) : SidecarClient {

    private val scope = parentScope ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val ownsScope = parentScope == null

    private val lifecycleMutex = Mutex() // serialises connect / re-dial
    private val writeMutex = Mutex() // serialises outbound frames over the one connection
    private val nextId = AtomicLong(1)

    private val _state = MutableStateFlow<SidecarConnectionState>(SidecarConnectionState.Disconnected)
    override val state: StateFlow<SidecarConnectionState> = _state.asStateFlow()

    private val _pushes = MutableSharedFlow<SidecarPush>(extraBufferCapacity = PUSH_BUFFER)
    override val pushes: SharedFlow<SidecarPush> = _pushes.asSharedFlow()

    @Volatile
    private var session: Session? = null

    /** Per-connection state. A new connection gets a new [Session]; teardown is scoped to one. */
    private class Session(val connection: Connection) {
        val codec = FrameCodec(connection)
        val pending = ConcurrentHashMap<Long, CompletableDeferred<SidecarFrame>>()
        val streams = ConcurrentHashMap<Long, Channel<JsonElement>>()
        val handshake = CompletableDeferred<SidecarFrame.Welcome>()

        @Volatile
        var readerJob: Job? = null

        @Volatile
        var capabilities: Set<String> = emptySet()
    }

    override fun capabilities(): Set<String> =
        (state.value as? SidecarConnectionState.Ready)?.capabilities ?: emptySet()

    override suspend fun connect() {
        lifecycleMutex.withLock {
            if (session != null && state.value is SidecarConnectionState.Ready) return
            _state.value = SidecarConnectionState.Connecting

            val connection = try {
                transport.connect()
            } catch (e: SidecarException) {
                _state.value = SidecarConnectionState.Disconnected
                throw e
            }

            val sess = Session(connection)
            sess.readerJob = scope.launch { runReader(sess) }
            try {
                writeFrame(sess, SidecarFrame.Hello(token(), protocolVersion))
                val welcome = withTimeout(handshakeTimeoutMillis) { sess.handshake.await() }
                sess.capabilities = welcome.capabilities
                session = sess
                _state.value = SidecarConnectionState.Ready(welcome.capabilities)
            } catch (e: Throwable) {
                sess.readerJob?.cancel()
                connection.close()
                _state.value = SidecarConnectionState.Disconnected
                throw mapHandshakeError(e)
            }
        }
    }

    override suspend fun request(method: String, params: JsonElement?): JsonElement? {
        val sess = ensureConnected()
        val id = nextId.getAndIncrement()
        val reply = CompletableDeferred<SidecarFrame>()
        sess.pending[id] = reply
        try {
            writeFrame(sess, SidecarFrame.Request(id, method, params))
            return when (val frame = reply.await()) {
                is SidecarFrame.Response -> frame.result
                is SidecarFrame.Failure -> throw SidecarRequestException(frame.error)
                else -> throw SidecarProtocolException("unexpected reply for request id=$id, method=$method")
            }
        } finally {
            sess.pending.remove(id)
        }
    }

    override fun openStream(method: String, params: JsonElement?): Flow<JsonElement> = flow {
        val sess = ensureConnected()
        val id = nextId.getAndIncrement()
        val channel = Channel<JsonElement>(capacity = STREAM_BUFFER)
        sess.streams[id] = channel
        try {
            writeFrame(sess, SidecarFrame.Request(id, method, params))
            for (event in channel) {
                emit(event) // a Failure closes the channel with its cause → the for-loop rethrows it
            }
            // Falling out of the loop = the Helper sent StreamEnd (normal completion); nothing to cancel.
        } catch (e: CancellationException) {
            // The *collector* cancelled mid-stream — tell the Helper to stop (e.g. release the mic). Run
            // on the client scope so the collector's cancellation doesn't abort the Cancel write itself.
            scope.launch { runCatching { writeFrame(sess, SidecarFrame.Cancel(id)) } }
            throw e
        } finally {
            sess.streams.remove(id)
            channel.close()
        }
    }

    override fun close() {
        val sess = session
        session = null
        _state.value = SidecarConnectionState.Disconnected
        sess?.let { teardown(it, SidecarConnectionLostException("Sidecar client closed")) }
        if (ownsScope) scope.cancel()
    }

    // --- internals ---------------------------------------------------------------------------------

    private suspend fun ensureConnected(): Session {
        session?.let { if (state.value is SidecarConnectionState.Ready) return it }
        connect()
        return session ?: throw SidecarUnavailableException("Sidecar is not connected")
    }

    private suspend fun writeFrame(sess: Session, frame: SidecarFrame) = writeMutex.withLock {
        try {
            sess.codec.writeFrame(frame)
        } catch (e: IOException) {
            throw SidecarConnectionLostException("failed writing ${frame::class.simpleName}", e)
        }
    }

    private suspend fun runReader(sess: Session) {
        try {
            while (true) {
                val frame = sess.codec.readFrame() ?: break // clean EOF between frames
                dispatch(sess, frame)
            }
            teardown(sess, SidecarConnectionLostException("Sidecar connection closed by the Helper"))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            teardown(sess, SidecarConnectionLostException("Sidecar connection lost", e))
        }
    }

    private fun dispatch(sess: Session, frame: SidecarFrame) {
        when (frame) {
            is SidecarFrame.Welcome -> sess.handshake.complete(frame)
            is SidecarFrame.Response -> sess.pending.remove(frame.id)?.complete(frame)
            is SidecarFrame.StreamData -> {
                val channel = sess.streams[frame.id] ?: return
                if (channel.trySend(frame.event).isFailure) {
                    sess.streams.remove(frame.id)
                    channel.close(SidecarProtocolException("stream id=${frame.id} buffer overflow"))
                }
            }
            is SidecarFrame.StreamEnd -> sess.streams.remove(frame.id)?.close()
            is SidecarFrame.Push -> _pushes.tryEmit(SidecarPush(frame.topic, frame.payload))
            is SidecarFrame.Failure -> dispatchFailure(sess, frame)
            // Client→server frames; a Helper should never send these. Ignore defensively.
            is SidecarFrame.Hello, is SidecarFrame.Request, is SidecarFrame.Cancel -> Unit
        }
    }

    private fun dispatchFailure(sess: Session, frame: SidecarFrame.Failure) {
        val id = frame.id
        if (id == null) {
            // Connection-level failure (e.g. a rejected Hello) — fail the handshake if it is pending.
            sess.handshake.completeExceptionally(
                SidecarSecurityException("Sidecar handshake rejected: code=${frame.error.code}, message=${frame.error.message}"),
            )
            return
        }
        val reply = sess.pending.remove(id)
        if (reply != null) {
            reply.complete(frame)
        } else {
            sess.streams.remove(id)?.close(SidecarRequestException(frame.error))
        }
    }

    /** Fail everything bound to [sess] and, if it is still the live session, mark the client disconnected. */
    private fun teardown(sess: Session, cause: SidecarException) {
        if (session === sess) {
            session = null
            _state.value = SidecarConnectionState.Disconnected
        }
        sess.handshake.completeExceptionally(cause)
        sess.pending.values.forEach { it.completeExceptionally(cause) }
        sess.pending.clear()
        sess.streams.values.forEach { it.close(cause) }
        sess.streams.clear()
        sess.readerJob?.cancel()
        sess.connection.close()
    }

    private fun mapHandshakeError(e: Throwable): Throwable = when (e) {
        is TimeoutCancellationException -> SidecarUnavailableException("Sidecar handshake timed out", e)
        is SidecarException -> e
        else -> SidecarConnectionLostException("Sidecar handshake failed", e)
    }

    private companion object {
        const val STREAM_BUFFER = 64
        const val PUSH_BUFFER = 64
    }
}

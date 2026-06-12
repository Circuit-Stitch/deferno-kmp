package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

/**
 * A scriptable [SidecarClient] for capability-port unit tests ([SidecarSpeechPortTest]): every protocol
 * outcome a port must map (connect refused, capability absent, permission states, stream events,
 * mid-stream failures, an unreadable reply) is a knob. The real client over the real socket is
 * exercised separately by [SidecarClientE2ETest].
 */
internal class FakeSidecarClient(
    var advertisedCapabilities: Set<String> = setOf(
        SidecarCapabilities.Permissions,
        SidecarCapabilities.SpeechTranscribe,
    ),
    /** Thrown by [connect] and by a collected stream (mirroring the client's lazy (re)connect). */
    var connectFailure: SidecarException? = null,
    /** The canned [SidecarMethods.QueryPermission] status (what the default [requestAnswer] encodes). */
    var permissionStatus: PermissionStatusValue = PermissionStatusValue.GRANTED,
    /** Thrown by [request] instead of answering. */
    var requestFailure: SidecarException? = null,
    /** The events a collected stream replays. */
    var streamEvents: List<JsonElement> = emptyList(),
    /** Thrown by the stream after [streamEvents] (a Helper failure / dropped connection mid-utterance). */
    var streamFailure: SidecarException? = null,
) : SidecarClient {

    /** What [request] answers — defaults to the canned [permissionStatus] wire reply; override to script
     *  a no-content ack (`{ null }`) or a malformed reply (`{ buildJsonObject {} }`). */
    var requestAnswer: () -> JsonElement? = {
        SidecarJson.encodeToJsonElement(
            PermissionStatusWire.serializer(),
            PermissionStatusWire(capability = SidecarPermissionCapabilities.Speech, status = permissionStatus),
        )
    }

    private val _state = MutableStateFlow<SidecarConnectionState>(SidecarConnectionState.Disconnected)
    override val state: StateFlow<SidecarConnectionState> = _state

    private val _pushes = MutableSharedFlow<SidecarPush>()
    override val pushes: SharedFlow<SidecarPush> = _pushes

    /** Script an unsolicited Helper push (e.g. a [SidecarTopics.PermissionChanged], #120). */
    suspend fun push(push: SidecarPush) = _pushes.emit(push)

    var connects: Int = 0
        private set
    var requests: Int = 0
        private set
    val openedStreams: MutableList<String> = mutableListOf()
    var lastRequestMethod: String? = null
        private set
    var lastRequestParams: JsonElement? = null
        private set

    override suspend fun connect() {
        connects++
        connectFailure?.let { throw it }
        _state.value = SidecarConnectionState.Ready(advertisedCapabilities)
    }

    override fun capabilities(): Set<String> =
        (state.value as? SidecarConnectionState.Ready)?.capabilities ?: emptySet()

    override suspend fun request(method: String, params: JsonElement?): JsonElement? {
        requests++
        lastRequestMethod = method
        lastRequestParams = params
        requestFailure?.let { throw it }
        return requestAnswer()
    }

    override fun openStream(method: String, params: JsonElement?): Flow<JsonElement> = flow {
        openedStreams += method
        connectFailure?.let { throw it }
        streamEvents.forEach { emit(it) }
        streamFailure?.let { throw it }
    }

    override fun close() = Unit
}

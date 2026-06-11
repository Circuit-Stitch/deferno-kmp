package com.circuitstitch.deferno.core.speech

import com.circuitstitch.deferno.core.sidecar.PermissionStatusValue
import com.circuitstitch.deferno.core.sidecar.PermissionStatusWire
import com.circuitstitch.deferno.core.sidecar.SidecarCapabilities
import com.circuitstitch.deferno.core.sidecar.SidecarClient
import com.circuitstitch.deferno.core.sidecar.SidecarConnectionState
import com.circuitstitch.deferno.core.sidecar.SidecarException
import com.circuitstitch.deferno.core.sidecar.SidecarJson
import com.circuitstitch.deferno.core.sidecar.SidecarPush
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.JsonElement

/**
 * A scriptable [SidecarClient] for the [SidecarSpeechToText] unit tests: every protocol outcome the
 * engine must map (connect refused, capability absent, permission states, stream events, mid-stream
 * failures) is a knob. The real client against the real socket is exercised separately by
 * [SidecarSpeechToTextSelectorE2ETest].
 */
internal class FakeSidecarClient(
    var advertisedCapabilities: Set<String> = setOf(
        SidecarCapabilities.Permissions,
        SidecarCapabilities.SpeechTranscribe,
    ),
    /** Thrown by [connect] and by a collected stream (mirroring the client's lazy (re)connect). */
    var connectFailure: SidecarException? = null,
    /** The canned [SidecarMethods.QueryPermission] status. */
    var permissionStatus: PermissionStatusValue = PermissionStatusValue.GRANTED,
    /** Thrown by [request] instead of answering. */
    var requestFailure: SidecarException? = null,
    /** The events a collected stream replays. */
    var streamEvents: List<JsonElement> = emptyList(),
    /** Thrown by the stream after [streamEvents] (a Helper failure / dropped connection mid-utterance). */
    var streamFailure: SidecarException? = null,
) : SidecarClient {

    private val _state = MutableStateFlow<SidecarConnectionState>(SidecarConnectionState.Disconnected)
    override val state: StateFlow<SidecarConnectionState> = _state
    override val pushes: SharedFlow<SidecarPush> = MutableSharedFlow()

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
        return SidecarJson.encodeToJsonElement(
            PermissionStatusWire.serializer(),
            PermissionStatusWire(capability = "speech", status = permissionStatus),
        )
    }

    override fun openStream(method: String, params: JsonElement?): Flow<JsonElement> = flow {
        openedStreams += method
        connectFailure?.let { throw it }
        streamEvents.forEach { emit(it) }
        streamFailure?.let { throw it }
    }

    override fun close() = Unit
}

package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * The **global-hotkey capability port** (#125, ADR-0024): a typed facade over [SidecarClient] for the
 * `hotkeys` capability — [register]/[unregister] system-wide key bindings and observe their [fires].
 * Registering an already-registered id replaces it (rebinding is one call); the Helper unregisters
 * everything this client registered when its connection closes. Calls degrade gracefully with
 * [SidecarUnavailableException] when no Helper is bound.
 */
class SidecarHotkeyPort(private val client: SidecarClient) {

    /** Whether the connected Helper advertises `hotkeys` (false while disconnected — connect first). */
    fun isAvailable(): Boolean = SidecarCapabilities.Hotkeys in client.capabilities()

    /**
     * Register (or rebind) the global hotkey [id] to [key]+[modifiers].
     *
     * @throws SidecarRequestException [SidecarErrorCode.INVALID_PARAMS] for an unknown
     *   [key][SidecarHotkeyKeys] or empty [modifiers]; [SidecarErrorCode.UNAVAILABLE] when the OS
     *   refuses the registration (`hotkey-unavailable`).
     */
    suspend fun register(id: Long, key: String, modifiers: Set<HotkeyModifier>) {
        client.request(
            SidecarMethods.RegisterHotkey,
            SidecarJson.encodeToJsonElement(
                RegisterHotkeyWire.serializer(),
                RegisterHotkeyWire(id, key, modifiers),
            ),
        )
    }

    /** Unregister the global hotkey [id] (idempotent — an unknown id still acks). */
    suspend fun unregister(id: Long) {
        client.request(
            SidecarMethods.UnregisterHotkey,
            SidecarJson.encodeToJsonElement(UnregisterHotkeyWire.serializer(), UnregisterHotkeyWire(id)),
        )
    }

    /** The registered-binding id, once per fire. Hot, no replay — collect before registering. */
    val fires: Flow<Long> = client.pushes
        .filter { it.topic == SidecarTopics.HotkeyFired }
        .mapNotNull { push ->
            // Tolerant at the seam: a malformed payload is dropped, never thrown at a collector.
            runCatching {
                SidecarJson.decodeFromJsonElement(HotkeyFiredWire.serializer(), push.payload)
            }.getOrNull()
        }
        .map { it.id }
}

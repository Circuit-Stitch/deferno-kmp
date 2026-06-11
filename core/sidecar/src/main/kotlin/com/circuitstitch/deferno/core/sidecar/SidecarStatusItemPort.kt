package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map

/**
 * The **status-item capability port** (#125, ADR-0024): a typed facade over [SidecarClient] for the
 * `statusItem` capability — show/hide the Helper's menu-bar status item and observe its [clicks].
 * The Helper removes the item when this client's connection closes, so it appears only while the app
 * runs; calls degrade gracefully with [SidecarUnavailableException] when no Helper is bound.
 */
class SidecarStatusItemPort(private val client: SidecarClient) {

    /** Whether the connected Helper advertises `statusItem` (false while disconnected — connect first). */
    fun isAvailable(): Boolean = SidecarCapabilities.StatusItem in client.capabilities()

    /** Show or hide the menu-bar status item. */
    suspend fun setVisible(visible: Boolean) {
        client.request(
            SidecarMethods.SetStatusItem,
            SidecarJson.encodeToJsonElement(SetStatusItemWire.serializer(), SetStatusItemWire(visible)),
        )
    }

    /** One emission per click on the visible status item. Hot, no replay — collect before showing. */
    val clicks: Flow<Unit> = client.pushes
        .filter { it.topic == SidecarTopics.StatusItemClicked }
        .map { }
}

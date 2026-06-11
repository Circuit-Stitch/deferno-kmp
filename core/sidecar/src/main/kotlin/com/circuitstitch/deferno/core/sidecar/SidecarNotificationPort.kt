package com.circuitstitch.deferno.core.sidecar

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapNotNull

/**
 * The **Notification capability port** (#123, ADR-0024): a typed facade over [SidecarClient] for the
 * `notifications` capability — [post] a user-visible OS notification, introspect the notification
 * [permission], and observe its out-of-band [permissionChanges]. The wire stays inside this port;
 * consumers see only the typed surface (the condense-at-the-edge shape #119/#120 use, ADR-0011).
 *
 * Permission semantics follow the contract: [permission] **introspects, never prompts**; the first
 * [post] against a `not_determined` state is what fires the OS authorization prompt (the Helper pushes
 * [SidecarTopics.PermissionChanged] as it settles — observable via [permissionChanges], the signal
 * #120's device-local App-setting surface persists). A [post] without a grant throws
 * [SidecarRequestException] with [SidecarErrorCode.UNAVAILABLE], and every call degrades gracefully
 * with [SidecarUnavailableException] when no Helper is bound.
 */
class SidecarNotificationPort(private val client: SidecarClient) {

    /** Whether the connected Helper advertises `notifications` (false while disconnected — connect first). */
    fun isAvailable(): Boolean = SidecarCapabilities.Notifications in client.capabilities()

    /**
     * Deliver [notification] through the OS notification surface.
     *
     * @throws SidecarRequestException the Helper refused — [SidecarErrorCode.UNAVAILABLE] (no grant),
     *   [SidecarErrorCode.INVALID_PARAMS] (empty title), or [SidecarErrorCode.INTERNAL] (delivery failed).
     * @throws SidecarUnavailableException no Helper bound (degrade gracefully).
     */
    suspend fun post(notification: PostNotificationWire) {
        client.request(
            SidecarMethods.PostNotification,
            SidecarJson.encodeToJsonElement(PostNotificationWire.serializer(), notification),
        )
    }

    /** The current notification permission state — introspection only, never prompts. */
    suspend fun permission(): PermissionStatusValue {
        val result = client.request(
            SidecarMethods.QueryPermission,
            SidecarJson.encodeToJsonElement(
                QueryPermissionWire.serializer(),
                QueryPermissionWire(SidecarPermissionCapabilities.Notifications),
            ),
        ) ?: return PermissionStatusValue.UNKNOWN
        return SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), result).status
    }

    /**
     * The notification permission state as it changes out-of-band ([SidecarTopics.PermissionChanged]
     * pushes filtered to the `notifications` capability). Hot, no replay — collect before triggering.
     */
    val permissionChanges: Flow<PermissionStatusValue> = client.pushes
        .filter { it.topic == SidecarTopics.PermissionChanged }
        .mapNotNull { push ->
            // Tolerant at the seam: a malformed payload is dropped, never thrown at a collector.
            runCatching {
                SidecarJson.decodeFromJsonElement(PermissionStatusWire.serializer(), push.payload)
            }.getOrNull()
        }
        .filter { it.capability == SidecarPermissionCapabilities.Notifications }
        .map { it.status }
}

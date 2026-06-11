package com.circuitstitch.deferno.core.sidecar

import kotlinx.serialization.Serializable

/**
 * The **wire** form of an OS notification to deliver — the [SidecarMethods.PostNotification] params
 * (#123, ADR-0024). A contract-owned type (the Sidecar module stays a leaf); the success reply is an
 * empty ack, and a missing grant fails the request with [SidecarErrorCode.UNAVAILABLE]
 * (`notification-permission-denied`) so callers can route to the permission UX.
 *
 * **Privacy (ADR-0009):** the title/body are user content (a task name, a due date), so — like
 * [TranscriptWire] — both are **redacted from [toString]**; a stray log can never leak them.
 */
@Serializable
data class PostNotificationWire(
    /** The notification's headline. Must be non-empty ([SidecarErrorCode.INVALID_PARAMS] otherwise). */
    val title: String,
    /** The optional supporting line under the title. */
    val body: String? = null,
) {
    override fun toString(): String =
        "PostNotificationWire(title=<redacted ${title.length} chars>, body=" +
            (body?.let { "<redacted ${it.length} chars>" } ?: "null") + ")"
}

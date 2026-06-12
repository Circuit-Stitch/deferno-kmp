package com.circuitstitch.deferno.core.sidecar

/**
 * Stable names for the Sidecar [[Sidecar protocol]] — the protocol version, the seed methods/topics,
 * and the advertised capability ids. Centralised here so the client, the stub Helper, the tests, and
 * the `contracts/sidecar/` spec all agree on the exact strings.
 *
 * The two seed methods exist to prove the contract's three traffic shapes end-to-end (#118); the real
 * capability surface grows over the *same* contract in #119/#120/#123+ (ADR-0024 — "additive over the
 * same socket contract").
 */

/** The protocol version this client speaks; bumped only on a breaking wire change. Sent in [SidecarFrame.Hello]. */
const val SIDECAR_PROTOCOL_VERSION: Int = 1

/** Method names a client sends in a [SidecarFrame.Request]. */
object SidecarMethods {
    /** Request/response: query one capability's current permission state → [PermissionStatusWire]. */
    const val QueryPermission: String = "queryPermission"

    /**
     * Request/response: resolve one capability's permission *without* engaging the capability (#120) —
     * prompts iff `not_determined`, answers the settled [PermissionStatusWire], pushes
     * [SidecarTopics.PermissionChanged] as it settles.
     */
    const val RequestPermission: String = "requestPermission"

    /** Server stream: subscribe to on-device dictation; yields [TranscriptWire] events until cancelled. */
    const val SubscribeTranscript: String = "subscribeTranscript"

    /** Request/response: post a user-visible OS notification ([PostNotificationWire] params, #123). */
    const val PostNotification: String = "postNotification"

    /** Request/response: show/hide the Helper's menu-bar status item ([SetStatusItemWire] params, #125). */
    const val SetStatusItem: String = "setStatusItem"

    /** Request/response: register a global hotkey ([RegisterHotkeyWire] params; replaces its id, #125). */
    const val RegisterHotkey: String = "registerHotkey"

    /** Request/response: unregister a global hotkey ([UnregisterHotkeyWire] params; idempotent, #125). */
    const val UnregisterHotkey: String = "unregisterHotkey"
}

/** Topics a Helper sends in an unsolicited [SidecarFrame.Push]. */
object SidecarTopics {
    /** A capability's permission state changed out-of-band (payload: [PermissionStatusWire]). */
    const val PermissionChanged: String = "permissionChanged"

    /** The visible menu-bar status item was clicked (payload: `{}`, #125). */
    const val StatusItemClicked: String = "statusItemClicked"

    /** A registered global hotkey fired (payload: [HotkeyFiredWire], #125). */
    const val HotkeyFired: String = "hotkeyFired"
}

/** Capability ids a Helper advertises in [SidecarFrame.Welcome] (D4). */
object SidecarCapabilities {
    /** The Helper can answer [SidecarMethods.QueryPermission] and emit [SidecarTopics.PermissionChanged]. */
    const val Permissions: String = "permissions"

    /** The Helper hosts an on-device speech engine reachable via [SidecarMethods.SubscribeTranscript]. */
    const val SpeechTranscribe: String = "speech.transcribe"

    /** The Helper can deliver OS notifications via [SidecarMethods.PostNotification] (#123). */
    const val Notifications: String = "notifications"

    /** The Helper can host a menu-bar status item ([SidecarMethods.SetStatusItem], #125). */
    const val StatusItem: String = "statusItem"

    /** The Helper can register global hotkeys ([SidecarMethods.RegisterHotkey], #125). */
    const val Hotkeys: String = "hotkeys"
}

/**
 * The capability ids carried inside [PermissionStatusWire.capability] / [QueryPermissionWire.capability]
 * (the Swift Helper's `SidecarPermissionCapability`) — which *permission* a status is about, distinct
 * from the [SidecarCapabilities] a Helper advertises.
 */
object SidecarPermissionCapabilities {
    const val Microphone: String = "mic"
    const val Speech: String = "speech"
    const val Notifications: String = "notifications"
}

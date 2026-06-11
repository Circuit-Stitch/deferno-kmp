import Foundation

/// Stable names for the Sidecar protocol — the Swift mirror of `core/sidecar`'s `SidecarProtocol.kt`
/// and `contracts/sidecar/protocol-v1.md`. The JVM client and this helper are two implementations of
/// **one** contract; these constants must agree string-for-string.

/// The protocol version this helper speaks; sent in `welcome`. Bumped only on a breaking wire change.
public let sidecarProtocolVersion: Int = 1

/// 1 MiB — the frame size cap (`contracts/sidecar/protocol-v1.md`); an over-cap length is a protocol
/// error, never an allocation.
public let sidecarMaxFrameBytes: Int = 1 * 1024 * 1024

/// Method names a client sends in a `request`.
public enum SidecarMethods {
    /// Request/response: query one capability's current permission state → `PermissionStatus`.
    public static let queryPermission = "queryPermission"
    /// Server stream: subscribe to on-device dictation; yields `TranscriptEvent`s until cancelled.
    public static let subscribeTranscript = "subscribeTranscript"
    /// Request/response: post a user-visible OS notification (`PostNotificationRequest` params, #123).
    public static let postNotification = "postNotification"
}

/// Topics the helper sends in an unsolicited `push`.
public enum SidecarTopics {
    /// A capability's permission state changed out-of-band (payload: `PermissionStatus`).
    public static let permissionChanged = "permissionChanged"
}

/// Capability ids the helper advertises in `welcome`.
public enum SidecarCapabilities {
    /// Can answer `queryPermission` and emit `permissionChanged`.
    public static let permissions = "permissions"
    /// Hosts an on-device speech engine reachable via `subscribeTranscript`.
    public static let speechTranscribe = "speech.transcribe"
    /// Can deliver OS notifications via `postNotification` (#123).
    public static let notifications = "notifications"
}

/// The capability ids used inside a `PermissionStatus.capability` field (ADR-0024).
public enum SidecarPermissionCapability {
    public static let microphone = "mic"
    public static let speech = "speech"
    public static let notifications = "notifications"
}

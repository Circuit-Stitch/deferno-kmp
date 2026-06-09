import Foundation

/// A capability's permission state on the wire — the `queryPermission` result and the
/// `permissionChanged` push payload. Mirrors `core/sidecar`'s `PermissionStatusWire`; the JVM side
/// decodes tolerantly (an unrecognized `status` coerces to `unknown`).
public enum PermissionStatusValue: String {
    case granted
    case denied
    case notDetermined = "not_determined"
    case restricted
    case unknown
}

/// `{ "capability": string, "status": ... }`. Carries no private content.
public struct PermissionStatus: Equatable {
    public let capability: String
    public let status: PermissionStatusValue

    public init(capability: String, status: PermissionStatusValue) {
        self.capability = capability
        self.status = status
    }

    /// Encode to the opaque payload the wire carries.
    public var json: JSONValue {
        .object([
            "capability": .string(capability),
            "status": .string(status.rawValue),
        ])
    }
}

/// The wire form of a dictation event, carried in `stream_data.event` on the `subscribeTranscript`
/// stream. Mirrors `core/sidecar`'s `TranscriptWire`.
///
/// **Privacy (ADR-0009/0018):** `partial`/`final` text is privacy-critical — the recognized audio never
/// crosses this seam (the helper emits text, not PCM), and the text is never logged. `reason` is a
/// **non-PII** cause.
public enum TranscriptEvent: Equatable {
    case partial(text: String)
    case final(text: String)
    case failure(reason: String)

    /// Encode to the opaque payload the wire carries.
    public var json: JSONValue {
        switch self {
        case .partial(let text):
            return .object(["type": .string("partial"), "text": .string(text)])
        case .final(let text):
            return .object(["type": .string("final"), "text": .string(text)])
        case .failure(let reason):
            return .object(["type": .string("failure"), "reason": .string(reason)])
        }
    }
}

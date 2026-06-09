import Foundation

/// The v1 error codes (`contracts/sidecar/protocol-v1.md`). The JVM client coerces an unknown code to
/// `unknown`, so a helper may emit others; these are the ones the helper produces.
public enum SidecarErrorCode: String {
    /// The helper does not implement `method`.
    case unknownMethod = "unknown_method"
    /// `params` were missing or malformed.
    case invalidParams = "invalid_params"
    /// Handshake rejected (bad/absent token). Connection-level (`failure` with no `id`).
    case unauthenticated = "unauthenticated"
    /// The capability exists but isn't available now (permission denied, engine busy).
    case unavailable = "unavailable"
    /// The helper failed internally.
    case `internal` = "internal"
    /// A protocol violation (unframeable/oversize/unexpected frame).
    case protocolViolation = "protocol"
    /// Tolerant-decode fallback for an unrecognized code (the helper never *emits* this; it mirrors the
    /// JVM client's `coerceInputValues` so decoding a future code can't throw).
    case unknown = "unknown"
}

/// A wire error object: `{ "code", "message", "details"? }`. `message` is a **non-PII** human-readable
/// summary; `details` is opaque (and, like every payload, must never be logged — ADR-0009).
public struct SidecarError: Equatable {
    public let code: SidecarErrorCode
    public let message: String
    public let details: JSONValue?

    public init(_ code: SidecarErrorCode, _ message: String, details: JSONValue? = nil) {
        self.code = code
        self.message = message
        self.details = details
    }
}

import Foundation

/// A live server-side stream the client can cancel (it opened a `subscribeTranscript`). Cancelling
/// releases the underlying resource (the mic) — the contract says a cancelled stream's helper "ceases
/// work and need not send `stream_end`".
public protocol TranscriptHandle: AnyObject {
    func cancel()
}

/// The capability surface a connection serves — the seam between the protocol plumbing (handshake,
/// framing, correlation) and *what* the helper actually does. Two implementations:
///
/// - `RealCapabilityProvider` — real TCC + on-device SFSpeech (production).
/// - `CannedCapabilityProvider` — fixed responses mirroring the JVM stub helper, so the **real binary**
///   is a drop-in for the stub in the contract-parity integration test (no TCC / mic needed).
///
/// One provider is created **per connection**, so its `permissionChangeSink` routes pushes to that
/// connection; process-wide resource exclusivity (the single mic) lives in the provider implementation.
public protocol CapabilityProvider: AnyObject {
    /// Capability ids to advertise in `welcome`.
    var capabilities: [String] { get }

    /// Set by the connection: how the provider emits an unsolicited `permissionChanged` push (e.g. after
    /// a TCC request changes a status). Routed to this connection's write queue.
    var permissionChangeSink: ((PermissionStatus) -> Void)? { get set }

    /// Answer `queryPermission` (introspection only — never prompts). The optional `push` is an
    /// unsolicited `permissionChanged` to emit right after the response (the canned provider uses it to
    /// mirror the stub; the real provider returns `nil`).
    func queryPermission(params: JSONValue?) -> (response: PermissionStatus, push: PermissionStatus?)

    /// Open a transcript stream. `onEvent` delivers `partial`/`final`/`failure`; `onEnd` fires exactly
    /// once at natural completion or failure (NOT on `cancel()` of the returned handle, where the helper
    /// stays silent per the contract). Returns a handle the connection cancels on client `cancel`/teardown.
    func startTranscript(onEvent: @escaping (TranscriptEvent) -> Void, onEnd: @escaping () -> Void) -> TranscriptHandle
}

/// A handle that does nothing — returned when a stream is rejected synchronously (e.g. engine busy).
final class NoopTranscriptHandle: TranscriptHandle {
    func cancel() {}
}

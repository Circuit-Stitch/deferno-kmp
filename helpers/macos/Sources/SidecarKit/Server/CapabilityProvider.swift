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

    /// Answer `requestPermission` (#120): resolve the capability's permission *without* engaging the
    /// capability — fire the OS prompt iff `not_determined` (the completion may wait on the person),
    /// then complete with the **settled** state; any other state completes immediately, unprompted (a
    /// TCC denial is terminal — only System Settings flips it). The optional `push` mirrors
    /// `queryPermission`'s (the canned provider pushes; the real provider pushes a settling prompt
    /// through `permissionChangeSink` instead and completes `push: nil`).
    func requestPermission(
        params: JSONValue?,
        completion: @escaping (_ response: PermissionStatus, _ push: PermissionStatus?) -> Void
    )

    /// Open a transcript stream. `onEvent` delivers `partial`/`final`/`failure`; `onEnd` fires exactly
    /// once at natural completion or failure (NOT on `cancel()` of the returned handle, where the helper
    /// stays silent per the contract). Returns a handle the connection cancels on client `cancel`/teardown.
    func startTranscript(onEvent: @escaping (TranscriptEvent) -> Void, onEnd: @escaping () -> Void) -> TranscriptHandle

    /// Deliver a user-visible OS notification (#123). Completes with `nil` on success (the connection
    /// acks with an empty `response`) or a `SidecarError` — `unavailable` without a grant (the real
    /// provider prompts first on `not_determined`, pushing `permissionChanged` as it settles), or
    /// `internal` when the OS refuses delivery. Params were already validated by the connection.
    func postNotification(_ request: PostNotificationRequest, completion: @escaping (SidecarError?) -> Void)

    /// Show/hide this connection's menu-bar status item (#125). `onClick` fires once per click while
    /// visible (the connection routes it as a `statusItemClicked` push); the first call's closure is
    /// kept for the connection's lifetime. Completes `nil` on success.
    func setStatusItem(visible: Bool, onClick: @escaping () -> Void, completion: @escaping (SidecarError?) -> Void)

    /// Register (or — same `id` — rebind) a global hotkey (#125); `onFire` fires per press (routed as a
    /// `hotkeyFired` push). Completes `nil` on success or `unavailable` when the OS refuses the binding.
    /// Params were already validated by the connection.
    func registerHotkey(_ request: RegisterHotkeyRequest, onFire: @escaping () -> Void, completion: @escaping (SidecarError?) -> Void)

    /// Unregister the hotkey `id` (idempotent — an unknown id still completes `nil`, #125).
    func unregisterHotkey(id: Int64, completion: @escaping (SidecarError?) -> Void)

    /// The connection closed: release per-connection UI state — remove the status item, unregister
    /// every hotkey this connection registered (#125). Open transcript streams are cancelled separately.
    func connectionClosed()
}

/// A handle that does nothing — returned when a stream is rejected synchronously (e.g. engine busy).
final class NoopTranscriptHandle: TranscriptHandle {
    func cancel() {}
}

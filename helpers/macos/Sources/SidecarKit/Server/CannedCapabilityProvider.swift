import Foundation

/// A `CapabilityProvider` with the **exact canned behaviour of the JVM stub helper**
/// (`core/sidecar`'s `StubHelper`), so the *real signed binary* — run with `--contract-fixtures` — is a
/// drop-in for the stub in the contract-parity integration test. It proves the helper's real codec,
/// handshake, streaming, push, and cancel against the real JVM client with **no TCC / mic dependency**;
/// the real SFSpeech + TCC paths are exercised separately (and are human-gated). Mirrors:
///
/// - `queryPermission` → a `response` echoing the queried capability (default speech) **and** a
///   follow-on `permissionChanged` push (same status);
/// - `requestPermission` → the canned TCC prompt (#120): a `not_determined` status settles to
///   `requestOutcome` (any other status reports itself unchanged — a denial is terminal, re-requesting
///   never re-prompts), then the same response + follow-on push shape as a query;
/// - `subscribeTranscript` → `partial "hel"`, `partial "hello wor"`, `final "hello world"`, `stream_end`,
///   with gaps so a collector can cancel mid-stream;
/// - `postNotification` → an empty ack when the canned status is granted, else `unavailable`
///   (`notification-permission-denied`) — no `UNUserNotificationCenter` is touched (#123);
/// - `setStatusItem` → an empty ack; showing additionally simulates one click (an observable
///   `statusItemClicked` push) — no AppKit is touched (#125);
/// - `registerHotkey` → an empty ack then one simulated fire (`hotkeyFired` push); `unregisterHotkey`
///   → an idempotent empty ack — no Carbon is touched (#125);
/// - `cancel` → stops that stream.
public final class CannedCapabilityProvider: CapabilityProvider {

    public let capabilities = [
        SidecarCapabilities.permissions,
        SidecarCapabilities.speechTranscribe,
        SidecarCapabilities.notifications,
        SidecarCapabilities.statusItem,
        SidecarCapabilities.hotkeys,
    ]
    public var permissionChangeSink: ((PermissionStatus) -> Void)?

    /// The canned status the stub reports / pushes (`GRANTED` by default, like the stub). Mutable for
    /// the same reason the stub's is: a `requestPermission` against `not_determined` settles it (#120).
    private var permissionStatus: PermissionStatusValue

    /// What a `requestPermission` against a `not_determined` status settles it to — the canned
    /// "person answered the TCC prompt" (#120), mirroring the stub's `requestOutcome`.
    private let requestOutcome: PermissionStatusValue

    public init(
        permissionStatus: PermissionStatusValue = .granted,
        requestOutcome: PermissionStatusValue = .granted
    ) {
        self.permissionStatus = permissionStatus
        self.requestOutcome = requestOutcome
    }

    public func queryPermission(params: JSONValue?) -> (response: PermissionStatus, push: PermissionStatus?) {
        let capability = params?.string("capability") ?? SidecarPermissionCapability.speech
        let status = PermissionStatus(capability: capability, status: permissionStatus)
        return (response: status, push: status) // stub answers, then pushes the same — observable push
    }

    public func requestPermission(
        params: JSONValue?,
        completion: @escaping (PermissionStatus, PermissionStatus?) -> Void
    ) {
        let capability = params?.string("capability") ?? SidecarPermissionCapability.speech
        // The canned TCC prompt (#120): only not_determined "prompts" and settles; anything else
        // reports itself unchanged (a denial is terminal — the contract).
        if permissionStatus == .notDetermined { permissionStatus = requestOutcome }
        let status = PermissionStatus(capability: capability, status: permissionStatus)
        completion(status, status) // answer, then push the settled state — the stub's observable push
    }

    public func postNotification(_ request: PostNotificationRequest, completion: @escaping (SidecarError?) -> Void) {
        completion(
            permissionStatus == .granted
                ? nil
                : SidecarError(.unavailable, "notification-permission-denied")
        )
    }

    public func setStatusItem(
        visible: Bool,
        onClick: @escaping () -> Void,
        completion: @escaping (SidecarError?) -> Void
    ) {
        completion(nil)
        // A canned "click" right after showing, so the push path is observable without a real menu
        // bar (the same trick as queryPermission's follow-on push; mirrors the JVM stub).
        if visible { onClick() }
    }

    public func registerHotkey(
        _ request: RegisterHotkeyRequest,
        onFire: @escaping () -> Void,
        completion: @escaping (SidecarError?) -> Void
    ) {
        completion(nil)
        onFire() // a canned "fire" right after registering — same observability trick
    }

    public func unregisterHotkey(id: Int64, completion: @escaping (SidecarError?) -> Void) {
        completion(nil) // idempotent ack, like the stub
    }

    public func connectionClosed() {
        // Nothing canned to release.
    }

    public func startTranscript(
        onEvent: @escaping (TranscriptEvent) -> Void,
        onEnd: @escaping () -> Void
    ) -> TranscriptHandle {
        let handle = CannedTranscriptHandle()
        DispatchQueue(label: "com.circuitstitch.deferno.sidecar.canned").async {
            let steps: [TranscriptEvent] = [
                .partial(text: "hel"),
                .partial(text: "hello wor"),
                .final(text: "hello world"),
            ]
            for (index, event) in steps.enumerated() {
                if handle.isCancelled { return } // cancelled → stop, stay silent (no stream_end)
                onEvent(event)
                if index < steps.count - 1 { Thread.sleep(forTimeInterval: 0.05) }
            }
            if handle.isCancelled { return }
            onEnd()
        }
        return handle
    }

    private final class CannedTranscriptHandle: TranscriptHandle {
        private let lock = NSLock()
        private var cancelled = false
        var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }
        func cancel() { lock.lock(); cancelled = true; lock.unlock() }
    }
}

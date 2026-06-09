import Foundation

/// A `CapabilityProvider` with the **exact canned behaviour of the JVM stub helper**
/// (`core/sidecar`'s `StubHelper`), so the *real signed binary* — run with `--contract-fixtures` — is a
/// drop-in for the stub in the contract-parity integration test. It proves the helper's real codec,
/// handshake, streaming, push, and cancel against the real JVM client with **no TCC / mic dependency**;
/// the real SFSpeech + TCC paths are exercised separately (and are human-gated). Mirrors:
///
/// - `queryPermission` → a `response` **and** a follow-on `permissionChanged` push (same status);
/// - `subscribeTranscript` → `partial "hel"`, `partial "hello wor"`, `final "hello world"`, `stream_end`,
///   with gaps so a collector can cancel mid-stream;
/// - `cancel` → stops that stream.
public final class CannedCapabilityProvider: CapabilityProvider {

    public let capabilities = [SidecarCapabilities.permissions, SidecarCapabilities.speechTranscribe]
    public var permissionChangeSink: ((PermissionStatus) -> Void)?

    /// The canned status the stub reports / pushes (`GRANTED` by default, like the stub).
    private let permissionStatus: PermissionStatusValue

    public init(permissionStatus: PermissionStatusValue = .granted) {
        self.permissionStatus = permissionStatus
    }

    public func queryPermission(params: JSONValue?) -> (response: PermissionStatus, push: PermissionStatus?) {
        let status = PermissionStatus(capability: SidecarPermissionCapability.speech, status: permissionStatus)
        return (response: status, push: status) // stub answers, then pushes the same — observable push
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

import Foundation
import Speech
import UserNotifications

/// The production `CapabilityProvider`: real macOS TCC + on-device SFSpeech + Notification Center
/// (ADR-0024).
///
/// - `queryPermission` introspects the live mic/Speech/notification state (never prompts).
/// - `subscribeTranscript` ensures Speech **and** mic authorization (firing the real TCC prompts on
///   first use, and pushing `permissionChanged` as states settle), then streams on-device dictation.
///   Only one transcription runs process-wide — the mic is exclusive — so a second concurrent subscribe
///   fails fast with `engine-busy`.
/// - `postNotification` ensures notification authorization the same way (prompt on first use, push as
///   it settles), then delivers through `UNUserNotificationCenter` (#123).
public final class RealCapabilityProvider: CapabilityProvider {

    public let capabilities: [String]
    public var permissionChangeSink: ((PermissionStatus) -> Void)?

    private let workQueue = DispatchQueue(label: "com.circuitstitch.deferno.sidecar.real")

    public init() {
        // Advertise speech only if the OS can construct an en-US recognizer at all (graceful degradation,
        // ADR-0025): a consumer then keeps whisper-in-JVM rather than calling a method doomed to fail.
        var caps = [SidecarCapabilities.permissions]
        if SFSpeechRecognizer(locale: Locale(identifier: "en-US")) != nil {
            caps.append(SidecarCapabilities.speechTranscribe)
        }
        // Same degradation for notifications (#123): UNUserNotificationCenter exists only for a
        // bundle-hosted process (the packaged helper, #122) — a bare dev binary must not offer it.
        if SidecarPermissions.notificationCenterAvailable {
            caps.append(SidecarCapabilities.notifications)
        }
        // And for the menu-bar status item + global hotkeys (#125): both need the window server, so a
        // headless run (no GUI session) simply doesn't offer them.
        if GuiSession.available {
            caps.append(SidecarCapabilities.statusItem)
            caps.append(SidecarCapabilities.hotkeys)
        }
        self.capabilities = caps
    }

    public func queryPermission(params: JSONValue?) -> (response: PermissionStatus, push: PermissionStatus?) {
        // The capability to query; default to speech (the headline dictation gate). #120 drives mic too.
        let capability = params?.string("capability") ?? SidecarPermissionCapability.speech
        return (response: SidecarPermissions.status(forCapability: capability), push: nil)
    }

    public func startTranscript(
        onEvent: @escaping (TranscriptEvent) -> Void,
        onEnd: @escaping () -> Void
    ) -> TranscriptHandle {
        // The mic is a single, process-wide resource — reject a second concurrent dictation immediately.
        guard MicSlot.tryAcquire() else {
            workQueue.async {
                onEvent(.failure(reason: "engine-busy"))
                onEnd()
            }
            return NoopTranscriptHandle()
        }

        let handle = RealTranscriptHandle()
        let release = Once { MicSlot.release() }
        // If a cancel lands during the (possibly long) TCC-prompt window — before any transcriber is
        // adopted — release the mic slot promptly instead of pinning it until the prompt resolves.
        handle.onCancelBeforeStart = { release.run() }

        workQueue.async { [weak self] in
            guard let self else { release.run(); onEnd(); return }

            self.ensureAuthorized { granted, reason in
                if handle.isCancelled {
                    release.run(); onEnd(); return
                }
                guard granted else {
                    onEvent(.failure(reason: reason))
                    release.run(); onEnd(); return
                }
                guard let transcriber = SpeechTranscriber(localeIdentifier: "en-US") else {
                    onEvent(.failure(reason: "recognizer-unavailable"))
                    release.run(); onEnd(); return
                }
                // Adopt + start ATOMICALLY with respect to cancel: holding the handle's lock across both
                // closes the adopt→start window where a cancel could otherwise interleave and leave the
                // mic + audio engine running forever (the engine started after finish() already ran).
                let started = handle.adoptAndStart(transcriber) {
                    transcriber.start(onEvent: onEvent, onEnd: {
                        release.run()
                        onEnd()
                    })
                }
                if !started {
                    // Cancelled before we could start — nothing was started; release + end here.
                    release.run(); onEnd()
                }
            }
        }
        return handle
    }

    public func postNotification(_ request: PostNotificationRequest, completion: @escaping (SidecarError?) -> Void) {
        guard SidecarPermissions.notificationCenterAvailable else {
            completion(SidecarError(.unavailable, "notification-center-unavailable"))
            return
        }
        workQueue.async { [weak self] in
            self?.ensureNotificationsAuthorized { granted in
                guard granted else {
                    completion(SidecarError(.unavailable, "notification-permission-denied"))
                    return
                }
                let content = UNMutableNotificationContent()
                content.title = request.title
                if let body = request.body { content.body = body }
                content.sound = .default
                let delivery = UNNotificationRequest(identifier: UUID().uuidString, content: content, trigger: nil)
                UNUserNotificationCenter.current().add(delivery) { error in
                    // Metadata only — the OS error is not echoed (it can embed content; ADR-0009).
                    completion(error == nil ? nil : SidecarError(.internal, "notification-post-failed"))
                }
            } ?? completion(SidecarError(.internal, "notification-post-failed"))
        }
    }

    // MARK: status item + hotkeys (#125)
    // All three run on this connection's single-threaded read loop (and `connectionClosed` on its
    // teardown), so the per-connection state below needs no locking; the process-wide pieces
    // (NSStatusBar, the Carbon registry) synchronise internally on the main thread.

    /// This connection's status item; created on first show, removed on hide/close.
    private var statusItemController: StatusItemController?

    /// This connection's live hotkey bindings by client-chosen id (re-register replaces, close releases).
    private var hotkeyRegistrations: [Int64: HotkeyRegistration] = [:]

    public func setStatusItem(
        visible: Bool,
        onClick: @escaping () -> Void,
        completion: @escaping (SidecarError?) -> Void
    ) {
        guard GuiSession.available else {
            completion(SidecarError(.unavailable, "no-gui-session"))
            return
        }
        if statusItemController == nil {
            statusItemController = StatusItemController(onClick: onClick)
        }
        statusItemController?.setVisible(visible)
        completion(nil)
    }

    public func registerHotkey(
        _ request: RegisterHotkeyRequest,
        onFire: @escaping () -> Void,
        completion: @escaping (SidecarError?) -> Void
    ) {
        guard GuiSession.available else {
            completion(SidecarError(.unavailable, "no-gui-session"))
            return
        }
        hotkeyRegistrations.removeValue(forKey: request.id)?.unregister() // same id → rebind
        guard let registration = HotkeyCenter.shared.register(
            key: request.key,
            modifiers: request.modifiers,
            onFire: onFire
        ) else {
            completion(SidecarError(.unavailable, "hotkey-unavailable"))
            return
        }
        hotkeyRegistrations[request.id] = registration
        completion(nil)
    }

    public func unregisterHotkey(id: Int64, completion: @escaping (SidecarError?) -> Void) {
        hotkeyRegistrations.removeValue(forKey: id)?.unregister()
        completion(nil) // idempotent — an unknown id still acks
    }

    public func connectionClosed() {
        statusItemController?.setVisible(false)
        statusItemController = nil
        hotkeyRegistrations.values.forEach { $0.unregister() }
        hotkeyRegistrations.removeAll()
    }

    // MARK: TCC sequencing (Speech then mic; fire prompts on notDetermined; push as states settle)

    private func ensureNotificationsAuthorized(_ done: @escaping (Bool) -> Void) {
        switch SidecarPermissions.notificationsStatus() {
        case .granted:
            done(true)
        case .notDetermined:
            SidecarPermissions.requestNotifications { [weak self] status in
                self?.pushPermission(SidecarPermissionCapability.notifications, status)
                done(status == .granted)
            }
        default:
            done(false)
        }
    }

    private func ensureAuthorized(_ completion: @escaping (_ granted: Bool, _ reason: String) -> Void) {
        ensureSpeech { [weak self] speechOk in
            guard speechOk else { completion(false, "speech-permission-denied"); return }
            self?.ensureMicrophone { micOk in
                completion(micOk, micOk ? "" : "microphone-permission-denied")
            } ?? completion(false, "microphone-permission-denied")
        }
    }

    private func ensureSpeech(_ done: @escaping (Bool) -> Void) {
        switch SidecarPermissions.speechStatus() {
        case .granted:
            done(true)
        case .notDetermined:
            SidecarPermissions.requestSpeech { [weak self] status in
                self?.pushPermission(SidecarPermissionCapability.speech, status)
                done(status == .granted)
            }
        default:
            done(false)
        }
    }

    private func ensureMicrophone(_ done: @escaping (Bool) -> Void) {
        switch SidecarPermissions.microphoneStatus() {
        case .granted:
            done(true)
        case .notDetermined:
            SidecarPermissions.requestMicrophone { [weak self] status in
                self?.pushPermission(SidecarPermissionCapability.microphone, status)
                done(status == .granted)
            }
        default:
            done(false)
        }
    }

    private func pushPermission(_ capability: String, _ status: PermissionStatusValue) {
        permissionChangeSink?(PermissionStatus(capability: capability, status: status))
    }
}

// MARK: - Mic exclusivity + handle

/// Process-wide single-mic gate: only one `subscribeTranscript` may hold the mic at a time.
private enum MicSlot {
    private static let lock = NSLock()
    private static var inUse = false

    static func tryAcquire() -> Bool {
        lock.lock(); defer { lock.unlock() }
        if inUse { return false }
        inUse = true
        return true
    }

    static func release() {
        lock.lock(); defer { lock.unlock() }
        inUse = false
    }
}

/// Cancellable handle for a real transcript stream; bridges a client `cancel` to the (possibly not-yet-
/// created) `SpeechTranscriber`.
private final class RealTranscriptHandle: TranscriptHandle {
    private let lock = NSLock()
    private var cancelled = false
    private var transcriber: SpeechTranscriber?

    /// Releases the mic slot if a cancel arrives before a transcriber is adopted (during the TCC prompt).
    var onCancelBeforeStart: (() -> Void)?

    var isCancelled: Bool { lock.lock(); defer { lock.unlock() }; return cancelled }

    /// Adopt the transcriber and run `start` **while holding the lock**, so a concurrent `cancel()` cannot
    /// interleave between adopt and start. Returns `false` (without starting) if already cancelled. `start`
    /// must not call back into this handle (it doesn't — it only touches the transcriber + release/onEnd).
    func adoptAndStart(_ transcriber: SpeechTranscriber, _ start: () -> Void) -> Bool {
        lock.lock(); defer { lock.unlock() }
        if cancelled { return false }
        self.transcriber = transcriber
        start()
        return true
    }

    func cancel() {
        lock.lock()
        cancelled = true
        let transcriber = self.transcriber
        // Only release early if nothing was adopted yet; otherwise stop() → finish() → onEnd releases.
        let earlyRelease = transcriber == nil ? onCancelBeforeStart : nil
        lock.unlock()
        transcriber?.stop()
        earlyRelease?()
    }
}

/// Runs a closure at most once (idempotent resource release across mutually-exclusive completion paths).
private final class Once {
    private let lock = NSLock()
    private var done = false
    private let body: () -> Void
    init(_ body: @escaping () -> Void) { self.body = body }
    func run() {
        lock.lock()
        let shouldRun = !done
        done = true
        lock.unlock()
        if shouldRun { body() }
    }
}

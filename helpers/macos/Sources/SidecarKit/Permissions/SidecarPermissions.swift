import Foundation
import AVFoundation
import Speech
import UserNotifications

/// The helper's TCC broker (ADR-0024) — it introspects and (on demand) requests the macOS mic + Speech
/// + notification permissions, mapping each to the wire `PermissionStatusValue`. TCC attributes these
/// grants to the helper's own signed identity (its Info.plist usage strings + Developer ID signature),
/// so the prompts the user sees carry *Deferno's* wording and the grants persist across rebuilds.
///
/// Introspection (`status…`) never prompts; `request…` is what fires the real TCC dialog.
public enum SidecarPermissions {

    public static func microphoneStatus() -> PermissionStatusValue {
        map(AVCaptureDevice.authorizationStatus(for: .audio))
    }

    public static func speechStatus() -> PermissionStatusValue {
        map(SFSpeechRecognizer.authorizationStatus())
    }

    /// Whether this process can host `UNUserNotificationCenter` at all (#123). The framework resolves
    /// the process's LaunchServices bundle proxy from the executable's **enclosing `.app` bundle**; a
    /// bare binary — even with an embedded `__info_plist` — has none, and `UNUserNotificationCenter
    /// .current()` then raises an **uncatchable** NSException. So a non-bundled helper (a dev
    /// `swift build` binary) must not advertise or touch the capability; the packaged helper (#122)
    /// runs from inside the app bundle, where notifications attribute to Deferno itself.
    public static var notificationCenterAvailable: Bool {
        Bundle.main.bundleURL.pathExtension == "app"
    }

    /// The notification authorization state; `.unknown` when the process can't host the center, or when
    /// the (async) settings query doesn't answer promptly.
    public static func notificationsStatus() -> PermissionStatusValue {
        guard notificationCenterAvailable else { return .unknown }
        // getNotificationSettings is async (it asks usernoted); bridge it for the synchronous
        // queryPermission path with a bounded wait rather than blocking the read loop indefinitely.
        let semaphore = DispatchSemaphore(value: 0)
        var status = PermissionStatusValue.unknown
        UNUserNotificationCenter.current().getNotificationSettings { settings in
            status = map(settings.authorizationStatus)
            semaphore.signal()
        }
        _ = semaphore.wait(timeout: .now() + 2)
        return status
    }

    /// Current state of a capability id (`"mic"` / `"speech"` / `"notifications"`); unknown id → `.unknown`.
    public static func status(forCapability capability: String) -> PermissionStatus {
        switch capability {
        case SidecarPermissionCapability.microphone:
            return PermissionStatus(capability: capability, status: microphoneStatus())
        case SidecarPermissionCapability.speech:
            return PermissionStatus(capability: capability, status: speechStatus())
        case SidecarPermissionCapability.notifications:
            return PermissionStatus(capability: capability, status: notificationsStatus())
        default:
            return PermissionStatus(capability: capability, status: .unknown)
        }
    }

    /// Request microphone access (fires the TCC prompt if `notDetermined`); reports the resulting state.
    public static func requestMicrophone(_ completion: @escaping (PermissionStatusValue) -> Void) {
        AVCaptureDevice.requestAccess(for: .audio) { _ in
            completion(microphoneStatus())
        }
    }

    /// Request Speech-recognition access (fires the TCC prompt if `notDetermined`); reports the result.
    public static func requestSpeech(_ completion: @escaping (PermissionStatusValue) -> Void) {
        SFSpeechRecognizer.requestAuthorization { _ in
            completion(speechStatus())
        }
    }

    /// Request notification authorization (fires the OS prompt if `notDetermined`); reports the result.
    public static func requestNotifications(_ completion: @escaping (PermissionStatusValue) -> Void) {
        guard notificationCenterAvailable else {
            completion(.unknown)
            return
        }
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            completion(notificationsStatus())
        }
    }

    // MARK: status mapping

    private static func map(_ status: AVAuthorizationStatus) -> PermissionStatusValue {
        switch status {
        case .authorized: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .unknown
        }
    }

    private static func map(_ status: SFSpeechRecognizerAuthorizationStatus) -> PermissionStatusValue {
        switch status {
        case .authorized: return .granted
        case .denied: return .denied
        case .restricted: return .restricted
        case .notDetermined: return .notDetermined
        @unknown default: return .unknown
        }
    }

    private static func map(_ status: UNAuthorizationStatus) -> PermissionStatusValue {
        switch status {
        // `.provisional` delivers quietly (no banners) but delivers — a grant for the port's purposes.
        case .authorized, .provisional: return .granted
        case .denied: return .denied
        case .notDetermined: return .notDetermined
        @unknown default: return .unknown
        }
    }
}

import Foundation
import AVFoundation
import Speech

/// The helper's TCC broker (ADR-0024) — it introspects and (on demand) requests the macOS mic + Speech
/// permissions, mapping each to the wire `PermissionStatusValue`. TCC attributes these grants to the
/// helper's own signed identity (its Info.plist usage strings + Developer ID signature), so the prompts
/// the user sees carry *Deferno's* wording and the grants persist across rebuilds.
///
/// Introspection (`status…`) never prompts; `request…` is what fires the real TCC dialog.
public enum SidecarPermissions {

    public static func microphoneStatus() -> PermissionStatusValue {
        map(AVCaptureDevice.authorizationStatus(for: .audio))
    }

    public static func speechStatus() -> PermissionStatusValue {
        map(SFSpeechRecognizer.authorizationStatus())
    }

    /// Current state of a capability id (`"mic"` / `"speech"`); an unknown capability → `.unknown`.
    public static func status(forCapability capability: String) -> PermissionStatus {
        switch capability {
        case SidecarPermissionCapability.microphone:
            return PermissionStatus(capability: capability, status: microphoneStatus())
        case SidecarPermissionCapability.speech:
            return PermissionStatus(capability: capability, status: speechStatus())
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
}

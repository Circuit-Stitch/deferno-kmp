import Deferno
import SwiftUI
import UserNotifications

/// App entry (#12, #35). Owns the shared component tree for the app's lifetime and hands its
/// `RootComponent` to SwiftUI. That tree is now the **real** shared shell over the DI graph
/// (`DefernoRoot` — the iOS analogue of `DefernoApplication` + `MainActivity`), not the in-memory
/// `DefernoDemo` scaffold: the Views render `RootComponent → Auth/Main → the Destination graph`
/// (ADR-0013/0017). Bridged by the hand-written SKIE-free bridge until SKIE supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    // The Brain dump mic recorder (#267) is shared: the Kotlin host drives it (record → on-device pipeline),
    // and the Brain dump overlay observes its `levels` for the spectrum — one engine, no second mic tap.
    @StateObject private var recorder: BrainDumpRecorder
    @State private var host: DefernoRoot
    // Retained because UNUserNotificationCenter.delegate is weak (#271).
    @State private var notificationDelegate: BrainDumpNotificationDelegate

    init() {
        let recorder = BrainDumpRecorder()
        _recorder = StateObject(wrappedValue: recorder)
        // On-device capabilities: dictation (#268, SFSpeech mic), and the Brain dump's on-device extraction
        // (#269) — the SpeechTranscriber file transcriber + the Apple Foundation Models inference engine.
        let host = DefernoRoot(
            recorder: recorder,
            dictation: IosDictation(),
            inference: IosInference(),
            fileTranscriber: IosFileTranscriber()
        )
        _host = State(initialValue: host)
        // Brain dump completion notification (#271): a tap routes to the Inbox through the shared shell.
        let delegate = BrainDumpNotificationDelegate()
        delegate.onOpenInbox = { host.forwardOpenInbox() }
        _notificationDelegate = State(initialValue: delegate)
        UNUserNotificationCenter.current().delegate = delegate
    }

    var body: some Scene {
        WindowGroup {
            RootView(root: host.root, recorder: recorder)
                // OAuth redirect fallback (ADR-0026, #137): sign-in normally runs in an in-app
                // `ASWebAuthenticationSession` sheet that captures its own redirect, but if the
                // registered `com.circuitstitch.deferno` scheme (CFBundleURLTypes, Info.plist) ever
                // re-enters the app from an external browser, the shared inbox still routes it.
                .onOpenURL { url in
                    host.forwardAuthRedirect(url: url.absoluteString)
                }
        }
    }
}

/// Routes a tapped Brain dump completion notification (#271) to the Inbox. `UNUserNotificationCenter.delegate`
/// is weak, so `DefernoApp` retains this; `onOpenInbox` is wired to the host's `forwardOpenInbox()`.
final class BrainDumpNotificationDelegate: NSObject, UNUserNotificationCenterDelegate {
    var onOpenInbox: () -> Void = {}

    /// Show the banner + play the sound even when the app is foreground (so a just-finished take still notifies).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .sound])
    }

    /// Tap → open the Inbox (only for the brain-dump category the Kotlin notifier sets).
    func userNotificationCenter(
        _ center: UNUserNotificationCenter,
        didReceive response: UNNotificationResponse,
        withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        if response.notification.request.content.categoryIdentifier == BrainDumpRecordingKt.BRAIN_DUMP_NOTIFICATION_CATEGORY {
            onOpenInbox()
        }
        completionHandler()
    }
}

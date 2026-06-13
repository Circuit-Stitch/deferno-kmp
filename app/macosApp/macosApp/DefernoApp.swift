import Deferno
import SwiftUI

/// macOS app entry (ADR-0029, Phase 1b). Owns the shared component tree for the app's lifetime and
/// hands its `RootComponent` to SwiftUI. That tree is the **real** shared shell over the DI graph
/// (`DefernoRoot` — the macOS analogue of `DefernoApplication` + `MainActivity`), not the in-memory
/// `DefernoDemoRoot` scaffold: the Views render `RootComponent → Auth/Main → the Destination graph`
/// (ADR-0013/0017). The app opens on the Auth shell; a pasted staging PAT flips the Active Account and
/// the Main shell renders over the real data layer. Bridged by the hand-written SKIE-free bridge until
/// SKIE supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    // Phase 2 (ADR-0029): the in-process dictation engine (SidecarKit `SpeechTranscriber`, on-device).
    // Phase 3 (ADR-0029): the in-process inference engine (Foundation Models, on-device). Both run under
    // this app's own identity (no Helper); the inference engine drives `host.draftTasks` (the Extractor).
    @State private var host = DefernoRoot(dictation: MacDictation(), inference: MacInference())
    @State private var showExtractor = false

    var body: some Scene {
        // A single `Window` (not a `WindowGroup`): Deferno is a one-window app, and the OAuth redirect
        // re-entering via the custom scheme must NOT spawn a second window — it has to land on the live
        // shell so the in-flight sign-in's inbox receives it (#189). The explicit title also names the
        // window "Deferno" regardless of the bundle name.
        Window("Deferno", id: "main") {
            RootView(root: host.root)
                // OAuth redirect (ADR-0026, #137): the system browser returns to the registered
                // `com.circuitstitch.deferno` scheme (project.yml URL types); forward it to the shared
                // inbox the in-flight `MacBrowserAuthenticator` awaits. On macOS this is the PRIMARY
                // capture path (#189), not just a fallback.
                .onOpenURL { url in
                    host.forwardAuthRedirect(url: url.absoluteString)
                }
                .sheet(isPresented: $showExtractor) {
                    DraftExtractorView(bridge: host.draftTasks)
                }
        }
        .commands {
            // The Phase-3 demo trigger lives in a menu (⌘⇧E), not the shared shell — it's a macOS-app
            // dev surface for exercising the on-device Extractor, not a shipped product flow yet.
            CommandMenu("Apple Intelligence") {
                Button("Extract Draft Tasks…") { showExtractor = true }
                    .keyboardShortcut("e", modifiers: [.command, .shift])
            }
        }
    }
}

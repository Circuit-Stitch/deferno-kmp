import Deferno
import SwiftUI

/// macOS app entry (ADR-0029, Phase 1). Owns the shared component tree for the app's lifetime and
/// hands its `RootComponent` to SwiftUI. For Phase 1 that tree is the **demo** shell over in-memory
/// fakes (`DefernoDemoRoot` — no backend, no DI graph, no encrypted DB), seeded with one Active
/// Account so the window opens on the Main shell (RootComponent → Main → the five Destinations +
/// Search/New overlays, ADR-0013/0017). The real `DefernoRoot` over the DI graph + paste-PAT sign-in
/// is Phase 1b. Bridged by the hand-written SKIE-free bridge until SKIE supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    // Phase 2 (ADR-0029): the in-process dictation engine (SidecarKit `SpeechTranscriber`, on-device).
    // Phase 3 (ADR-0029): the in-process inference engine (Foundation Models, on-device). Both run under
    // this app's own identity (no Helper); the inference engine drives `host.draftTasks` (the Extractor).
    @State private var host = DefernoDemoRoot(dictation: MacDictation(), inference: MacInference())
    @State private var showExtractor = false

    var body: some Scene {
        WindowGroup {
            RootView(root: host.root)
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

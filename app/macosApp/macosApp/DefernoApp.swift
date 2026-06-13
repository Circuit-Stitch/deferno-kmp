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
    // The New surface's mic drives it; TCC is attributed to this app's own identity.
    @State private var host = DefernoDemoRoot(dictation: MacDictation())

    var body: some Scene {
        WindowGroup {
            RootView(root: host.root)
        }
    }
}

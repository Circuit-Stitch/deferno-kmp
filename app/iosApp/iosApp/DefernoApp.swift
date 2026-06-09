import Deferno
import SwiftUI

/// App shell (issue #12). Owns the single shared component tree for the app's lifetime and hands it
/// to the SwiftUI Views. Today that tree is the in-memory `DefernoDemo` harness (#51) standing in for
/// the not-yet-built iOS app shell + DI (a follow-up, #68/ADR-0014); the Views are genuine renderers
/// of the shared Decompose components either way. Bridged by plain Kotlin→ObjC export today, and by
/// SKIE (deferred; see ../README.md) for idiomatic Swift once it supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    @State private var demo = DefernoDemo()

    var body: some Scene {
        WindowGroup {
            ContentView(demo: demo)
        }
    }
}

import Deferno
import SwiftUI

/// App entry (#12, #35). Owns the shared component tree for the app's lifetime and hands its
/// `RootComponent` to SwiftUI. That tree is now the **real** shared shell over the DI graph
/// (`DefernoRoot` — the iOS analogue of `DefernoApplication` + `MainActivity`), not the in-memory
/// `DefernoDemo` scaffold: the Views render `RootComponent → Auth/Main → the Destination graph`
/// (ADR-0013/0017). Bridged by the hand-written SKIE-free bridge until SKIE supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    @State private var host = DefernoRoot()

    var body: some Scene {
        WindowGroup {
            RootView(root: host.root)
        }
    }
}

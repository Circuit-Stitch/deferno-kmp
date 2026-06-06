import SwiftUI

// App shell (issue #12). The real app hosts the shared Decompose root from the `Deferno`
// framework once features expose it — bridged by plain Kotlin→ObjC export today, and by
// SKIE (deferred; see ../build.gradle.kts) for idiomatic Swift once it supports Kotlin 2.4.0.
@main
struct DefernoApp: App {
    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}

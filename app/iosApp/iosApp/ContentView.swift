import SwiftUI
import Deferno

// SwiftUI Views are centralized in this Xcode project in per-feature folders (ADR-0004).
// This stub reads its text from the shared `Deferno` framework (IosGreeting, defined in
// app/iosApp/src/iosMain) — the SKIE-free baseline. Once features expose their Decompose
// components (and a Kotlin-2.4.0-compatible SKIE is wired), these Views render shared state.
struct ContentView: View {
    private let greeting = IosGreeting().text

    var body: some View {
        Text(greeting)
            .padding()
    }
}

#Preview {
    ContentView()
}

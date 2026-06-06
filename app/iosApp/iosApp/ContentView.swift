import SwiftUI

// Scaffold placeholder. SwiftUI Views are centralized in this Xcode project in
// per-feature folders (ADR-0004); they render state from the shared `Deferno`
// framework once it exports the feature components.
struct ContentView: View {
    var body: some View {
        Text("Deferno — iOS scaffold")
            .padding()
    }
}

#Preview {
    ContentView()
}

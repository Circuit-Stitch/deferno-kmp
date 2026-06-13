import Deferno
import SwiftUI

/// The app root. A simple `TabView` over the two built feature Destinations — **Plan** (the calm
/// home, opened first per design-principles.md) and **Tasks** (the size-class-adaptive list/detail/
/// tree, #51). Both render the shared Decompose components handed over by the demo harness (`#51`);
/// the full Main shell (the nav suite + Calendar/Profile/Settings Destinations, ADR-0013) is a
/// follow-up. SwiftUI Views are centralized in this Xcode project in per-feature folders (ADR-0004).
struct ContentView: View {
    let demo: DefernoDemo

    var body: some View {
        TabView {
            PlanView(component: demo.plan.component)
                .tabItem { Label("Plan", systemImage: "sun.max") }
            TasksScreen(root: demo.tasks)
                .tabItem { Label("Tasks", systemImage: "checklist") }
        }
    }
}

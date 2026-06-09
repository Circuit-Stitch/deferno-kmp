import Deferno
import SwiftUI

/// The daily Plan pane (#51) — the app's calm home (design-principles.md: "open into today's Plan,
/// not the whole backlog"). A thin renderer of `PlanComponent`: observes today's ordered Tasks and
/// forwards taps (open the Task) / refresh, holding no logic of its own.
struct PlanView: View {
    let component: PlanComponent
    @StateObject private var state: StateFlowObserver<PlanState>

    init(component: PlanComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.planStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            PaneHeader(title: "Today") {
                Button("Refresh") { component.onRefresh() }
                    .frame(minHeight: Layout.minTouchTarget)
                    .disabled(value.isRefreshing)
            }
            if value.isRefreshing {
                LoadingStrip(label: "Refreshing your plan…")
            }
            if value.tasks.isEmpty && !value.isRefreshing {
                EmptyStateView(
                    title: "Your plan is clear",
                    message: "Nothing scheduled for today. Add something when you're ready — no pressure."
                )
            } else {
                List {
                    ForEach(value.tasks, id: \.stableKey) { task in
                        TaskRow(task: task, showsPin: false) { component.onTaskClicked(id: task.id) }
                            .listRowInsets(EdgeInsets())
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(Color(.systemBackground))
    }
}

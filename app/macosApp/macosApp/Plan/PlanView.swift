import Deferno
import SwiftUI

/// The Plan Destination host (#51) — a **tier-3 drill-down** (`PlanChild`: Dashboard ↔ Detail(task)).
/// A Plan tap pushes the Task's detail onto the Plan stack; a subtask drill pushes deeper. The single
/// adaptive shell bar (`MainShellView`) titles each surface and drives ← back, so the detail is hosted
/// header-less. Renders the active child inline, mirroring `SettingsView`'s tier-3 stack — no shell
/// overlay any more (the detail used to be a sheet).
struct PlanHostView: View {
    let plan: MainShellComponentDestinationChildPlan
    @StateObject private var stack: StateFlowObserver<MainShellComponentPlanChild>

    init(plan: MainShellComponentDestinationChildPlan) {
        self.plan = plan
        _stack = StateObject(wrappedValue: StateFlowObserver(plan.activeChild))
    }

    var body: some View {
        let child = stack.value
        if let dashboard = ShellBridgeKt.planChildDashboard(child: child) {
            PlanView(component: dashboard)
        } else if let detail = ShellBridgeKt.planChildDetail(child: child) {
            TaskDetailView(component: detail, showsHeader: false).id(BridgeKt.detailKey(component: detail))
        }
    }
}

/// The daily Plan pane (#51) — the app's calm home (design-principles.md: "open into today's Plan,
/// not the whole backlog"). A thin renderer of `PlanComponent`: observes today's ordered Tasks and
/// forwards taps (open the Task) / refresh, holding no logic of its own.
struct PlanView: View {
    let component: PlanComponent
    @StateObject private var state: StateFlowObserver<PlanState>

    init(component: PlanComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        // No PaneHeader: the single adaptive shell bar (MainShellView) titles "Today".
        VStack(spacing: 0) {
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
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

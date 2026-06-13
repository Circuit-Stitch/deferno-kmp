import Deferno
import SwiftUI

/// The Task list pane (#51). A thin renderer of `TaskListComponent`: it observes the component's
/// state and forwards taps/refresh to it, holding no logic of its own (ADR-0007).
struct TaskListView: View {
    let component: TaskListComponent
    @StateObject private var state: StateFlowObserver<TaskListState>

    init(component: TaskListComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.taskListStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            PaneHeader(title: "Tasks") {
                Button("Refresh") { component.onRefresh() }
                    .frame(minHeight: Layout.minTouchTarget)
                    .disabled(value.isRefreshing)
            }
            if value.isRefreshing {
                LoadingStrip(label: "Refreshing…")
            }
            if value.tasks.isEmpty && !value.isRefreshing {
                EmptyStateView(
                    title: "No tasks yet",
                    message: "When you add a task, it shows up here. One small step at a time."
                )
            } else {
                List {
                    ForEach(value.tasks, id: \.stableKey) { task in
                        TaskRow(task: task) { component.onTaskClicked(id: task.id) }
                            .listRowInsets(EdgeInsets())
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

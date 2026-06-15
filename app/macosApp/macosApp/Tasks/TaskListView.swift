import Deferno
import SwiftUI

/// The Task list pane (#51). A thin renderer of `TaskListComponent`: it observes the component's
/// state and forwards taps/refresh to it, holding no logic of its own (ADR-0007).
struct TaskListView: View {
    let component: TaskListComponent
    @StateObject private var state: StateFlowObserver<TaskListState>
    // Opens a task into its own detached window (#196). Only the list wires this trigger.
    @Environment(\.openWindow) private var openWindow

    init(component: TaskListComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.taskListStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            PaneHeader(title: "Tasks")
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
                            // Double-click or "Open in New Window" → a detached detail window (#196),
                            // keyed by the raw task id (the WindowGroup scene value).
                            .simultaneousGesture(
                                TapGesture(count: 2).onEnded {
                                    openWindow(id: "task-detail", value: task.stableKey)
                                }
                            )
                            .contextMenu {
                                Button("Open in New Window") {
                                    openWindow(id: "task-detail", value: task.stableKey)
                                }
                            }
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

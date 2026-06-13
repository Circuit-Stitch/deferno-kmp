import Deferno
import SwiftUI

/// The Task breakdown (tree) pane (#51). Thin renderer of `TaskTreeComponent`: shows the root's
/// direct children one level at a time — "decompose to defeat paralysis" (design-principles.md) —
/// forwarding a child tap (drill in) and close to the component.
struct TaskTreeView: View {
    let component: TaskTreeComponent
    @StateObject private var state: StateFlowObserver<TaskTreeState>

    init(component: TaskTreeComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.taskTreeStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            PaneHeader(
                title: value.root.map { "Steps in “\($0.title)”" } ?? "Steps",
                onBack: { component.onCloseClicked() }
            )
            if value.children.isEmpty {
                EmptyStateView(
                    title: "No smaller steps yet",
                    message: "Break this into next steps whenever you're ready — there's no rush."
                )
            } else {
                List {
                    ForEach(value.children, id: \.stableKey) { child in
                        TaskRow(task: child) { component.onChildClicked(id: child.id) }
                            .listRowInsets(EdgeInsets())
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

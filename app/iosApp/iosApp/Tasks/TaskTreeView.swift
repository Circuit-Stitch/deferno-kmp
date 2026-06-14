import Deferno
import SwiftUI

/// The Task breakdown (tree) pane (#51). Thin renderer of `TaskTreeComponent`: shows the root's
/// direct children one level at a time — "decompose to defeat paralysis" (design-principles.md) —
/// forwarding a child tap (drill in) and close to the component.
struct TaskTreeView: View {
    let component: TaskTreeComponent
    /// false when pushed onto the compact `NavigationStack`: the native bar owns title + back, so the
    /// in-pane `PaneHeader` is dropped. Default (regular-width split column) keeps it. See `TaskDetailView`.
    var showsHeader: Bool = true
    @StateObject private var state: StateFlowObserver<TaskTreeState>

    init(component: TaskTreeComponent, showsHeader: Bool = true) {
        self.component = component
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.taskTreeStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        let title = value.root.map { "Steps in “\($0.title)”" } ?? "Steps"
        VStack(spacing: 0) {
            if showsHeader {
                PaneHeader(title: title, onBack: { component.onCloseClicked() })
            }
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
        .background(Color(.systemBackground))
        .paneNavigationTitle(showsHeader ? nil : title)
    }
}

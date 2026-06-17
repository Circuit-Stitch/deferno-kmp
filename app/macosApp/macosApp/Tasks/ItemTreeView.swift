import Deferno
import SwiftUI

/// The Tasks Destination as one nested, collapsible **Item tree** across all four kinds (#227,
/// ADR-0034) — the cross-kind forest the old flat list + one-level drill pane were subsumed into. A
/// thin renderer of `ItemTreeComponent`: it observes the flattened `ItemTreeState.rows` and forwards
/// each row's toggle/open/refresh to the component, holding no logic of its own (ADR-0007).
struct ItemTreeView: View {
    let component: ItemTreeComponent
    @StateObject private var state: StateFlowObserver<ItemTreeState>

    init(component: ItemTreeComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.itemTreeStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            if value.isRefreshing {
                LoadingStrip(label: "Refreshing…")
            }
            if value.rows.isEmpty && !value.isRefreshing {
                EmptyStateView(
                    title: "No tasks yet",
                    message: "When you add a task, it shows up here. One small step at a time."
                )
            } else {
                List {
                    ForEach(value.rows, id: \.item.id) { row in
                        ItemRowView(
                            row: row,
                            onToggleExpand: { id, expanded in
                                component.onToggleExpand(id: id, currentlyExpanded: expanded)
                            },
                            onOpenDetail: { id, kind in
                                component.onOpenDetail(id: id, kind: kind)
                            }
                        )
                        .listRowInsets(EdgeInsets())
                    }
                }
                .listStyle(.plain)
                // The real macOS refresh is the View → Refresh / ⌘R menu command (wired to
                // tree.onRefresh() in ShellBridge); reads are local + reactive so the tree fills without
                // it. `.refreshable` is kept for parity — harmless where there's no pull gesture.
                .refreshable { component.onRefresh() }
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

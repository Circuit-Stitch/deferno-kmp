import Deferno
import SwiftUI

/// The Tasks Destination host (ADR-0034). The Destination is the nested, collapsible **Item tree**
/// (#227) as the primary pane, with its co-resident **detail** slot alongside (ADR-0007). The old flat
/// list + one-level drill pane are subsumed — a node's children are seen inline by expanding the tree.
///
/// macOS is always regular width, so this is a static split rather than a size-class-driven one: the
/// tree fills the window on its own, and opening a Task's detail (the row's trailing `›`) splits off a
/// detail column beside it. Per-pane minimums feed the window's dynamic floor (#194) so the detail is
/// never crushed to a sliver. All navigation state lives in the retained shared component — `detail` is
/// a co-resident slot — so this View holds no foreground state of its own.
struct TasksScreen: View {
    let root: TasksRoot
    @StateObject private var detail: OptionalStateFlowObserver<TaskDetailComponent>

    init(root: TasksRoot) {
        self.root = root
        _detail = StateObject(wrappedValue: OptionalStateFlowObserver(root.activeDetail))
    }

    var body: some View {
        if let detail = detail.value {
            HStack(spacing: 0) {
                ItemTreeView(component: root.tree)
                    .frame(minWidth: 280, idealWidth: 340)
                Divider()
                TaskDetailView(component: detail)
                    .id(BridgeKt.detailKey(component: detail))
                    .frame(minWidth: 250, maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            ItemTreeView(component: root.tree)
                .frame(minWidth: 280, maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

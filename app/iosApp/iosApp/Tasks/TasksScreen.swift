import Deferno
import SwiftUI

/// The Tasks Destination host — the **adaptive tier-2 Pane layout** (ADR-0007). It renders the shared
/// component's co-resident list/detail/tree slots as **one or two panes by horizontal size class**:
/// a side-by-side list + detail/tree on regular width (iPad, and Plus/Max iPhones in landscape), a
/// single foreground pane on compact width (most iPhones).
///
/// Adaptive off the size class, never a device check (ADR-0008). The two panes are a plain `HStack`
/// rather than `NavigationSplitView`: the split view's portrait collapse (it overlays the sidebar
/// behind a toggle) and its own column chrome fight the co-resident detail+tree model and these
/// Views' custom `PaneHeader`s, whereas a size-class-driven split keeps **both** panes visible and
/// predictable. All navigation state lives in the retained shared component — `detail`/`tree` are
/// co-resident slots and `activePane` is their foreground recency — so resizing (Split View / Stage
/// Manager) flips pane count without dropping what's open: this View holds no foreground state of its
/// own. In a single pane the drill is a native `NavigationStack` push (native back chevron + swipe-back):
/// the stack path is **derived** from the slot state via `tasksNavPath` (the present slots ordered by
/// `activePane` recency) and two-way synced — a native pop calls the foreground slot's `onCloseClicked()`,
/// which runs the component's own fallback so get/set stay consistent.
struct TasksScreen: View {
    let root: TasksRoot
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @StateObject private var detail: DetailSlotObserver
    @StateObject private var tree: TreeSlotObserver
    @StateObject private var activePane: ValueObserver<TaskPane>

    init(root: TasksRoot) {
        self.root = root
        _detail = StateObject(wrappedValue: DetailSlotObserver(root.detail))
        _tree = StateObject(wrappedValue: TreeSlotObserver(root.tree))
        _activePane = StateObject(wrappedValue: ValueObserver(root.activePane))
    }

    var body: some View {
        let slot = resolveSecondarySlot(
            activePane: activePane.value,
            hasDetail: detail.current != nil,
            hasTree: tree.current != nil
        )
        if horizontalSizeClass == .regular {
            NavigationStack {
                HStack(spacing: 0) {
                    TaskListView(component: root.list)
                        .frame(width: 340)
                    Divider()
                    secondaryPane(slot)
                        .frame(maxWidth: .infinity, maxHeight: .infinity)
                }
                .shellNavBar("Tasks")
            }
        } else {
            // Compact: the list is the stack root (carrying the Destination nav bar); a foregrounded
            // detail/tree is a native push. The path is derived from the slot state and synced back on pop.
            NavigationStack(path: navPath) {
                TaskListView(component: root.list)
                    .shellNavBar("Tasks")
                    .navigationDestination(for: TaskRoute.self, destination: pushedPane)
            }
        }
    }

    /// The compact `NavigationStack` path as a two-way binding over the Decompose slot state. The getter
    /// projects the slots to a route stack (`tasksNavPath`); the setter fires only on a native pop and
    /// closes the removed slot(s) top-first via `onCloseClicked()`, whose own fallback then re-derives the
    /// same shorter path — so get/set agree and there's no update loop. Pushes/reorders come from component
    /// intents (row/child taps) and flow through the getter, never the setter.
    private var navPath: Binding<[TaskRoute]> {
        Binding(
            get: { self.currentPath },
            set: { newValue in
                let old = self.currentPath
                guard newValue.count < old.count else { return }
                for route in old.suffix(from: newValue.count).reversed() {
                    switch route {
                    case .detail: self.detail.current?.onCloseClicked()
                    case .tree: self.tree.current?.onCloseClicked()
                    }
                }
            }
        )
    }

    private var currentPath: [TaskRoute] {
        tasksNavPath(
            activePane: activePane.value,
            hasDetail: detail.current != nil,
            hasTree: tree.current != nil
        )
    }

    /// A pushed secondary pane on compact width: the slot's View with its in-pane `PaneHeader` suppressed,
    /// so the native bar owns the title + back chevron.
    @ViewBuilder
    private func pushedPane(_ route: TaskRoute) -> some View {
        switch route {
        case .detail:
            if let detail = detail.current {
                TaskDetailView(component: detail, showsHeader: false).id(BridgeKt.detailKey(component: detail))
            }
        case .tree:
            if let tree = tree.current {
                TaskTreeView(component: tree, showsHeader: false).id(BridgeKt.treeKey(component: tree))
            }
        }
    }

    /// The detail (secondary) column on regular width: the chosen co-resident slot, or a gentle
    /// "pick a task" placeholder when nothing is open.
    @ViewBuilder
    private func secondaryPane(_ slot: SecondarySlot) -> some View {
        switch slot {
        case .detail:
            if let detail = detail.current {
                TaskDetailView(component: detail).id(BridgeKt.detailKey(component: detail))
            }
        case .tree:
            if let tree = tree.current {
                TaskTreeView(component: tree).id(BridgeKt.treeKey(component: tree))
            }
        case .none:
            EmptyStateView(
                title: "Nothing open",
                message: "Pick a task on the left to see its details here."
            )
        }
    }
}

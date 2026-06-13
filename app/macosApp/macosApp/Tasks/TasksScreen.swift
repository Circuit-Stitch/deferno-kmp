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
/// own. In a single pane it collapses to the most-recently-foregrounded slot via the shared
/// `resolveSecondarySlot` precedence, falling back to the list.
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
        if horizontalSizeClass != .compact {
            HStack(spacing: 0) {
                TaskListView(component: root.list)
                    .frame(width: 340)
                Divider()
                secondaryPane(slot)
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        } else {
            compactPane(slot)
        }
    }

    /// The single visible pane on compact width: the foregrounded slot, else the list. Each carries
    /// its own Back affordance (in its `PaneHeader`), so the swap reads predictably.
    @ViewBuilder
    private func compactPane(_ slot: SecondarySlot) -> some View {
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
            TaskListView(component: root.list)
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

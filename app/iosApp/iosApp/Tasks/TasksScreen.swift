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
    /// The compact `NavigationStack` path, **owned by SwiftUI** so a native back/swipe pops cleanly. It's
    /// kept in sync with the Decompose slot state (`currentPath`) in both directions via `.onChange` — see
    /// the compact branch in `body`.
    @State private var compactPath: [TaskRoute] = []

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
                // Tasks is the documented carve-out: the shell bar's title is empty for Tasks (its panes
                // carry their own headers), so this keeps a slim native title for the workspace column.
                .navigationTitle("Tasks")
                .navigationBarTitleDisplayMode(.inline)
            }
        } else {
            // Compact: the list is the stack root (carrying the Destination nav bar); a foregrounded
            // detail/tree is a native push. SwiftUI owns `compactPath` so a native back/swipe pops cleanly;
            // we mirror the Decompose slot state into it (push/recency) and, on a user pop, close the slot(s)
            // it removed. (Driving the path from a computed Binding instead let the slot's not-yet-updated
            // state re-assert the old path through the getter and bounce straight back to the detail on pop.)
            NavigationStack(path: $compactPath) {
                TaskListView(component: root.list)
                    .navigationTitle("Tasks")
                    .navigationBarTitleDisplayMode(.inline)
                    .navigationDestination(for: TaskRoute.self, destination: pushedPane)
            }
            .onAppear { compactPath = currentPath }
            .onChange(of: currentPath) { derived in
                // Slot state changed (a row/child tap pushed, recency reordered, or something closed it
                // elsewhere) → mirror it into the SwiftUI-owned path.
                if compactPath != derived { compactPath = derived }
            }
            .onChange(of: compactPath) { popped in
                // A native back/swipe shortened the path → close the slot(s) it removed (top-first). The
                // close re-derives the same shorter `currentPath`, so the mirror above is then a no-op.
                let derived = currentPath
                guard popped.count < derived.count else { return }
                for route in derived.suffix(from: popped.count).reversed() {
                    switch route {
                    case .detail: detail.current?.onCloseClicked()
                    case .tree: tree.current?.onCloseClicked()
                    }
                }
            }
        }
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

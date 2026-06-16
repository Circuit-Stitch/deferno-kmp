import Deferno
import SwiftUI

/// The Tasks Destination host — the **adaptive tier-2 Pane layout** (ADR-0007). It renders the shared
/// component's always-present **Item tree** (#227, ADR-0034) plus its co-resident **detail** slot as
/// **one or two panes by horizontal size class**: a side-by-side tree + detail on regular width (iPad,
/// and Plus/Max iPhones in landscape), a single foreground pane on compact width (most iPhones).
///
/// Adaptive off the size class, never a device check (ADR-0008). The two panes are a plain `HStack`
/// rather than `NavigationSplitView`: the split view's portrait collapse and its own column chrome fight
/// these Views' custom `PaneHeader`s, whereas a size-class-driven split keeps **both** panes visible and
/// predictable. All navigation state lives in the retained shared component — the tree is the primary
/// pane and `detail` is a co-resident slot — so resizing (Split View / Stage Manager) flips pane count
/// without dropping what's open: this View holds no foreground state of its own. In a single pane the
/// detail is a native `NavigationStack` push (native back chevron + swipe-back): the stack path is
/// **derived** from the slot state via `tasksNavPath` and two-way synced — a native pop calls the
/// detail's `onCloseClicked()`, which runs the component's own fallback so get/set stay consistent.
struct TasksScreen: View {
    let root: TasksRoot
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @StateObject private var detail: DetailSlotObserver
    /// The compact `NavigationStack` path, **owned by SwiftUI** so a native back/swipe pops cleanly. It's
    /// kept in sync with the Decompose slot state (`currentPath`) in both directions via `.onChange` — see
    /// the compact branch in `body`.
    @State private var compactPath: [TaskRoute] = []

    init(root: TasksRoot) {
        self.root = root
        _detail = StateObject(wrappedValue: DetailSlotObserver(root.detail))
    }

    var body: some View {
        let slot = resolveSecondarySlot(hasDetail: detail.current != nil)
        if horizontalSizeClass == .regular {
            NavigationStack {
                HStack(spacing: 0) {
                    ItemTreeView(component: root.tree)
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
            // Compact: the tree is the stack root (carrying the Destination nav bar); a foregrounded
            // detail is a native push. SwiftUI owns `compactPath` so a native back/swipe pops cleanly; we
            // mirror the Decompose slot state into it and, on a user pop, close the slot it removed.
            NavigationStack(path: $compactPath) {
                ItemTreeView(component: root.tree)
                    .navigationTitle("Tasks")
                    .navigationBarTitleDisplayMode(.inline)
                    .navigationDestination(for: TaskRoute.self, destination: pushedPane)
            }
            .onAppear { compactPath = currentPath }
            .onChange(of: currentPath) { derived in
                // Slot state changed (a row tap pushed detail, or it closed elsewhere) → mirror it into the
                // SwiftUI-owned path.
                if compactPath != derived { compactPath = derived }
            }
            .onChange(of: compactPath) { popped in
                // A native back/swipe shortened the path → close the slot it removed. The close re-derives
                // the same shorter `currentPath`, so the mirror above is then a no-op.
                let derived = currentPath
                guard popped.count < derived.count else { return }
                for route in derived.suffix(from: popped.count).reversed() {
                    switch route {
                    case .detail: detail.current?.onCloseClicked()
                    }
                }
            }
        }
    }

    private var currentPath: [TaskRoute] {
        tasksNavPath(hasDetail: detail.current != nil)
    }

    /// A pushed secondary pane on compact width: the detail's View with its in-pane `PaneHeader` suppressed,
    /// so the native bar owns the title + back chevron.
    @ViewBuilder
    private func pushedPane(_ route: TaskRoute) -> some View {
        switch route {
        case .detail:
            if let detail = detail.current {
                TaskDetailView(component: detail, showsHeader: false).id(BridgeKt.detailKey(component: detail))
            }
        }
    }

    /// The detail (secondary) column on regular width: the open detail, or a gentle "pick a task"
    /// placeholder when nothing is open.
    @ViewBuilder
    private func secondaryPane(_ slot: SecondarySlot) -> some View {
        switch slot {
        case .detail:
            if let detail = detail.current {
                TaskDetailView(component: detail).id(BridgeKt.detailKey(component: detail))
            }
        case .none:
            EmptyStateView(
                title: "Nothing open",
                message: "Pick a task on the left to see its details here."
            )
        }
    }
}

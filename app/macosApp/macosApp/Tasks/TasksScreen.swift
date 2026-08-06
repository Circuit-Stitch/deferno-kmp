import Deferno
import SwiftUI

/// The Tasks Destination host (ADR-0049). The Destination is the nested, collapsible **Item tree**
/// (#227) as the primary pane, with its co-resident **detail** slot alongside (ADR-0007). The old flat
/// list + one-level drill pane are subsumed — a node's children are seen inline by expanding the tree.
///
/// macOS is always regular width, so this is a static split rather than a size-class-driven one: the
/// tree fills the window on its own, and opening an item's detail (the row's trailing `›`) splits off a
/// detail column beside it. Per-pane minimums feed the window's dynamic floor (#194) so the detail is
/// never crushed to a sliver. All navigation state lives in the retained shared component — `detail` is
/// a co-resident slot — so this View holds no foreground state of its own.
///
/// **The slot holds one of two detail panes since #383**: a Task's full read/write `TaskDetailView`, or
/// the read-only `DefinitionDetailView` the three recurring kinds share. Swift cannot take the shared
/// sealed `DetailChild` apart, so the bridge answers which arm it is — the shape `PlanHostView` already
/// uses for `MainShellComponent.PlanChild`.
struct TasksScreen: View {
    let root: TasksRoot
    @StateObject private var detail: OptionalStateFlowObserver<TasksComponentDetailChild>

    init(root: TasksRoot) {
        self.root = root
        _detail = StateObject(wrappedValue: OptionalStateFlowObserver(root.activeDetail))
    }

    var body: some View {
        // ONE tree instance, always the leading child of the same `HStack` — never one per branch of an
        // `if/else`. The tree owns View state the user can see (the In today / Active / All filter and,
        // since #368, the inline search text + its scroll position), and SwiftUI tears down the state of a
        // View that moves between the two branches of a conditional: opening a task's detail would clear
        // the very search the user typed to find it, and re-subscribe the row Flow behind a visible blink.
        // The detail column is the only thing that appears and disappears here.
        HStack(spacing: 0) {
            ItemTreeView(component: root.tree)
                // Alone the tree fills the window; beside a detail it settles at its ideal 340 and lets
                // the detail take the slack (both greedy would split the window down the middle).
                .frame(
                    minWidth: 280,
                    idealWidth: 340,
                    maxWidth: detail.value == nil ? CGFloat.infinity : nil,
                    maxHeight: .infinity
                )
            if let child = detail.value {
                Divider()
                // `.id` on the Group, not on either pane: the slot re-keys when the open item changes,
                // and the identity is kind-qualified so a Task and a definition can never collapse onto
                // one view identity (see `BridgeKt.detailChildKey`).
                Group {
                    if let task = BridgeKt.taskDetailOrNull(child: child) {
                        TaskDetailView(component: task)
                    } else if let definition = BridgeKt.definitionDetailOrNull(child: child) {
                        DefinitionDetailView(component: definition)
                    }
                }
                .id(BridgeKt.detailChildKey(child: child))
                .frame(minWidth: 250, maxWidth: .infinity, maxHeight: .infinity)
            }
        }
    }
}

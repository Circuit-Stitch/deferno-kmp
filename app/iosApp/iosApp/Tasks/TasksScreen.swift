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
///
/// Tasks owns its **native** chrome (#263): rather than the shell's hand-drawn bar, the tree's stack
/// carries the large "Everything" title, a collapse-on-scroll `.searchable` field (inline-filtering the
/// forest), and the shared `ChromeToolbar` (☰ menu + create actions). The shell threads the chrome spec
/// + menu open down; cross-everything search stays the drawer's "Search" row.
struct TasksScreen: View {
    let root: TasksRoot
    /// The "Add a tree" footer opens the New create overlay; `onMenu` opens the shell drawer (the nav
    /// bar's leading ☰). Threaded from the shell.
    var onAdd: () -> Void = {}
    var onMenu: () -> Void = {}
    /// The shell-computed chrome (title is unused here — Tasks titles its own bar — but its `actions`
    /// drive the trailing toolbar items via `ChromeToolbar`).
    var chromeSpec: ChromeSpec
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @StateObject private var detail: DetailSlotObserver
    /// The native search text — inline-filters the loaded forest (#263). Lives here so it sits on the
    /// nav bar that owns the tree; passed down to `ItemTreeView` for the actual filtering.
    @State private var query = ""
    /// The compact `NavigationStack` path, **owned by SwiftUI** so a native back/swipe pops cleanly. It's
    /// kept in sync with the Decompose slot state (`currentPath`) in both directions via `.onChange` — see
    /// the compact branch in `body`.
    @State private var compactPath: [TaskRoute] = []

    init(
        root: TasksRoot,
        onAdd: @escaping () -> Void = {},
        onMenu: @escaping () -> Void = {},
        // Defaulted to an empty spec so the demo / preview call sites stay stable; the live shell always
        // threads the real `chrome.value` (its actions drive the trailing toolbar items).
        chromeSpec: ChromeSpec = ShellBridgeKt.emptyChrome()
    ) {
        self.root = root
        self.onAdd = onAdd
        self.onMenu = onMenu
        self.chromeSpec = chromeSpec
        _detail = StateObject(wrappedValue: DetailSlotObserver(root.detail))
    }

    var body: some View {
        if horizontalSizeClass == .regular {
            // iPad / regular width: a two-pane workspace with a native search that filters the tree column.
            // On regular width a persistent search field reads janky wedged into this custom two-pane +
            // reveal-drawer layout, so we use the iOS-26 `.minimize` behavior (#263): the field collapses to a
            // magnifying-glass toolbar button that expands on tap — the Files.app pattern the user asked for.
            // Pre-26 falls back to the standard field. Cross-everything search stays the drawer's row.
            if #available(iOS 26.0, *) {
                regularPane().searchToolbarBehavior(.minimize)
            } else {
                regularPane()
            }
        } else {
            // Compact: the tree is the stack root; a foregrounded detail is a native push. SwiftUI owns
            // `compactPath` so a native back/swipe pops cleanly; we mirror the Decompose slot state into it
            // and, on a user pop, close the slot it removed. The tree root now shows a real native bar —
            // the large "Everything" title + collapse-on-scroll search + shell actions (#263). A pushed
            // detail brings its own bar (back chevron + title) via `paneNavigationTitle`.
            NavigationStack(path: $compactPath) {
                ItemTreeView(component: root.tree, onAdd: onAdd, query: query)
                    .navigationTitle("Everything")
                    .navigationBarTitleDisplayMode(.large)
                    .toolbar { ChromeToolbar(spec: chromeSpec, onMenu: onMenu) }
                    // iOS 26 defaults iPhone search to a floating bottom pill that overlaps the list; pin it
                    // to the nav-bar drawer so it sits under the large "Everything" title and collapses on
                    // scroll (the Settings / Mail / Notes pattern the user asked for). #263
                    .searchable(
                        text: $query,
                        placement: .navigationBarDrawer(displayMode: .automatic),
                        prompt: "Search your trees"
                    )
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

    /// The regular/iPad two-pane workspace: the tree column (with its own "Everything" headline + inline
    /// search filter) beside the detail. `.searchable` here is the tree filter; the `.minimize` toolbar
    /// behavior (applied in `body`, iOS 26+) renders it as an expand-on-tap magnifying glass.
    @ViewBuilder
    private func regularPane() -> some View {
        NavigationStack {
            HStack(spacing: 0) {
                ItemTreeView(component: root.tree, onAdd: onAdd, query: query, showsColumnTitle: true)
                    .frame(width: 340)
                Divider()
                secondaryPane()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
            .navigationTitle("Tasks")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { ChromeToolbar(spec: chromeSpec, onMenu: onMenu) }
            .searchable(text: $query, prompt: "Search your trees")
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
    private func secondaryPane() -> some View {
        if let detail = detail.current {
            TaskDetailView(component: detail).id(BridgeKt.detailKey(component: detail))
        } else {
            EmptyStateView(
                title: "Nothing open",
                message: "Pick a task on the left to see its details here."
            )
        }
    }
}

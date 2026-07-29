import Deferno
import SwiftUI

/// The Tasks Destination as one nested, collapsible **Item tree** across all four kinds (#227,
/// ADR-0034) — the cross-kind forest the old flat list + one-level drill pane were subsumed into. A
/// thin renderer of `ItemTreeComponent`: it observes the flattened `ItemTreeState.rows` and forwards
/// each row's toggle/open/refresh to the component, holding no logic of its own (ADR-0007).
///
/// Restyled to the "See the trees" filigree (#231) + the **modal move mode** and **undo** of ADR-0034
/// decisions 6/8 (#228/#230), the macOS twin of the iOS `ItemTreeView` (#237). On macOS the move-mode
/// **entry** is a right-click **context menu → Move** — the native desktop idiom — rather than the iOS
/// touch long-press; the ↑ ↓ ‹ › move controls + Done and the top undo snackbar mirror iOS exactly. A
/// slim `{n} trees` count + a local filter (In today / Active / All) lead the list.
///
/// The column also carries its own **identity + inline search** (#263 parity, #368): iOS gets "Everything"
/// from a large nav title and the filter field from `.searchable(placement: .navigationBarDrawer)`, neither
/// of which exists here — the macOS window title for Tasks reads "Tasks" and there is no navigation bar to
/// hang a drawer on — so both live in a `treeHeader` pinned above the scroll, with the field bound to ⌘F.
///
/// ponytail: keyboard move (Alt+↑↓ / Tab) is out of #237's "buttons + undo" scope; the move math already
/// lives in the shared component, so add the key handlers here when the desktop keyboard pass lands (#368).
struct ItemTreeView: View {
    let component: ItemTreeComponent
    @StateObject private var state: StateFlowObserver<ItemTreeState>
    @Environment(\.defernoColors) private var colors

    /// Local in-list filter (#231), client-side over `state.rows` by terminal state. Defaults to **All**
    /// so the tree's existing behaviour is unchanged — the filter is an opt-in narrowing. `Item` carries
    /// only `isTerminal` (no working state on the cross-kind projection yet), so In today / Active both map
    /// to "non-terminal" and All shows everything (terminal rows de-emphasized).
    @State private var filterIndex: Int = 2

    /// The inline tree filter text (#263 parity, ⌘F-focusable). Empty → no filtering; otherwise a
    /// case-insensitive title match over the **loaded** forest that keeps each match's ancestor chain.
    /// Cross-everything search stays the shell toolbar's ⌕ (the Search overlay), not this — the two are
    /// deliberately different scopes, which is why this one never leaves the View.
    ///
    /// Scope caveat, shared with the iOS twin: `ItemTreeState.rows` is already fold-flattened, and
    /// `buildItemTree` emits "only an expanded parent's children", so a match inside a COLLAPSED subtree
    /// is not in `rows` and this cannot find it. The Search overlay is the everything-scope answer.
    @State private var query = ""
    @FocusState private var searchFocused: Bool

    private static let filters = [
        L.string("tasks_filter_in_today"),
        L.string("tasks_filter_active"),
        L.string("tasks_filter_all")
    ]

    init(component: ItemTreeComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        let inMoveMode = value.moveMode != nil
        let visibleRows = filteredRows(value.rows)
        let treeCount = value.rows.filter { $0.depth == 0 }.count

        ZStack(alignment: .top) {
            VStack(spacing: 0) {
                // Pinned above the scroll, not folded into `metaFilterBar`: a list row scrolls away, and
                // ⌘F focusing a `TextField` the List has scrolled out of view (or recycled) silently does
                // nothing. Hidden in move mode like the meta bar — the lifted row owns the surface.
                if !inMoveMode { treeHeader }

                List {
                    // A slim count + the local filter as the first row; hidden in move mode (the lifted-row
                    // focus owns the surface).
                    if !inMoveMode {
                        metaFilterBar(treeCount: treeCount, showBlocked: value.showBlocked)
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }

                    if value.isRefreshing {
                        LoadingStrip(label: L.string("tasks_tree_refreshing"))
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }

                    if visibleRows.isEmpty && !value.isRefreshing {
                        emptyState(
                            allEmpty: value.rows.isEmpty,
                            searching: !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        )
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    } else {
                        ForEach(visibleRows, id: \.item.id) { row in
                            ItemRowContainer(
                                row: row,
                                moveMode: value.moveMode,
                                menuState: value.menuStates[row.item.id],
                                canUndo: value.lastMove != nil,
                                component: component
                            )
                            .listRowInsets(EdgeInsets())
                            .listRowBackground(Color.clear)
                        }
                    }
                }
                .listStyle(.plain)
                // The List otherwise paints its own window background over the screen's warm surface; hide
                // it (+ clear row cells) so the parchment surface shows through.
                .scrollContentBackground(.hidden)
                // The real macOS refresh is the View → Refresh / ⌘R menu command (wired in ShellBridge);
                // reads are local + reactive so the tree fills without it. `.refreshable` is kept for parity.
                .refreshable { component.onRefresh() }

                // The contextual move-mode control (ADR-0034 decision 6, #228).
                if let move = value.moveMode {
                    MoveModeBar(
                        move: move,
                        onMoveUp: { component.onMoveUp() },
                        onMoveDown: { component.onMoveDown() },
                        onOutdent: { component.onOutdent() },
                        onIndent: { component.onIndent() },
                        onDone: { component.onExitMoveMode() }
                    )
                }
            }

            // Top-anchored undo snackbar (ADR-0034 decision 8, #230): offered after a move, reverting
            // through the single `undoLastMove` path. Auto-dismisses; hidden in move mode.
            if let undo = value.lastMove, !inMoveMode {
                UndoSnackbar(
                    operation: L.moveOperation(undo.operationKind.token),
                    moveKey: Int(undo.id),
                    onUndo: { component.undoLastMove() }
                )
                .padding(.horizontal, Layout.gutter)
                .padding(.top, 8)
                .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: value.lastMove?.id)
        .animation(.easeInOut(duration: 0.2), value: value.moveMode?.liftedId)
        // Move mode hides `treeHeader`, which OWNS the search field and its clear button — so a query
        // left standing would keep `filteredRows` narrowing the forest with nothing on screen to explain
        // why, and no way to clear it until the move ends. Worse than cosmetic: you would be choosing a
        // destination among siblings that aren't drawn. Dropping the query restores the whole forest,
        // which is the right set of move targets anyway. (No iOS twin for this: its field lives in the
        // nav bar and never hides.)
        .onChange(of: inMoveMode) { _, moving in
            if moving { query = "" }
        }
        .background(colors.background)
    }

    // MARK: - Pinned header (column identity + inline search)

    /// The tree column's identity + its inline filter field (#263 parity). "Everything" is the forest's
    /// own name — the window title says "Tasks" (the Destination), so without this the column reads as
    /// anonymous — and the field is the macOS shape of iOS's nav-bar-drawer `.searchable`.
    @ViewBuilder
    private var treeHeader: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(L.string("tasks_tree_title"))
                .font(.title3.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            HStack(spacing: 6) {
                DefernoIcon.search.image(size: 12)
                    .foregroundStyle(colors.inkMuted)
                    // Decoration: the field beside it is already labelled "Search".
                    .accessibilityHidden(true)
                TextField(L.string("search_initial_title"), text: $query)
                    .textFieldStyle(.roundedBorder)
                    .focused($searchFocused)
                    .accessibilityLabel(L.string("common_search"))
                if !query.isEmpty {
                    // Clearing leaves the caret where it is — the native search-field behaviour, and the
                    // user is usually retyping rather than leaving.
                    Button { query = "" } label: {
                        DefernoIcon.close.image(size: 11)
                            .foregroundStyle(colors.inkMuted)
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(L.string("common_clear"))
                }
            }
            // ⌘F focuses the field. A zero-sized, a11y-hidden Button is how a **view-scoped** SwiftUI
            // shortcut is declared: it lives in this window's responder chain, unlike a `DefernoApp`
            // `.commands` entry, which would have to reach across the View tree to find this field.
            // `.frame(0) + .opacity(0)` rather than `.hidden()` — the latter can drop the view from
            // layout and take its shortcut registration with it.
            Button("") { searchFocused = true }
                .keyboardShortcut("f", modifiers: .command)
                .buttonStyle(.plain)
                .frame(width: 0, height: 0)
                .opacity(0)
                .accessibilityHidden(true)
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.top, 8)
    }

    // MARK: - In-list header (count + filter)

    @ViewBuilder
    private func metaFilterBar(treeCount: Int, showBlocked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            MonoMeta(L.plural("tasks_tree_count", treeCount))
            HStack(spacing: 6) {
                ForEach(Array(Self.filters.enumerated()), id: \.offset) { i, label in
                    SelectableChip(
                        label: label,
                        selected: i == filterIndex,
                        prominence: .low,
                        compact: true
                    ) { filterIndex = i }
                }
                Spacer(minLength: 8)
                // The readiness axis (#290), distinct from the In-today/Active/All segment: ready-only by
                // default (rows arrive pre-pruned of `blocked` items + their subtrees); toggled on to reveal
                // them (still marked). Flips `showBlocked` on the shared component, never a client-side filter
                // (that would dangle the filigree rails).
                SelectableChip(
                    label: L.string("tasks_tree_show_blocked"),
                    selected: showBlocked,
                    prominence: .low,
                    compact: true
                ) { component.onSetShowBlocked(show: !showBlocked) }
            }
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 8)
    }

    private func filteredRows(_ rows: [ItemRow]) -> [ItemRow] {
        // In today / Active → non-terminal only; All → everything. Applied to the *match*, never to a
        // kept ancestor (an ancestor shows to root the match even if it's terminal).
        func stateMatch(_ row: ItemRow) -> Bool {
            switch filterIndex {
            case 0, 1: return !row.item.isTerminal
            default: return true
            }
        }
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return rows.filter(stateMatch) }

        // Title search keeps each match **plus its ancestor chain**, so the filigree spine + indentation
        // stay rooted (a bare leaf match would otherwise draw a rail hanging from a filtered-out parent).
        // The ancestor at each column is the most recent earlier row of shallower depth — reconstructed
        // from the pre-order `depth` sequence, no parentId on the bridged item needed.
        // ponytail: spine bools are still the full-tree ones, so a kept ancestor whose siblings were
        // filtered out can imply a sibling that isn't shown — same minor imperfection the segmented
        // filter already has, and far better than orphaned rails. Upgrade only if it reads wrong.
        var keep = Set<Int>()   // indices into `rows` to render
        var chain: [Int] = []   // chain[d] = index of the current ancestor at depth d; last entry is self
        for (i, row) in rows.enumerated() {
            let d = Int(row.depth)
            if chain.count > d { chain.removeLast(chain.count - d) }
            chain.append(i)
            if stateMatch(row) && row.item.title.localizedCaseInsensitiveContains(q) {
                keep.formUnion(chain) // the match (chain.last) + every ancestor
            }
        }
        return rows.enumerated().filter { keep.contains($0.offset) }.map(\.element)
    }

    /// `allEmpty` → the forest itself is empty. Otherwise rows exist but the active narrowing hid them:
    /// `searching` distinguishes a no-match query (clear the search) from the segmented filter hiding
    /// everything (switch to All) — one copy for both would misdirect half the time.
    @ViewBuilder
    private func emptyState(allEmpty: Bool, searching: Bool) -> some View {
        EmptyStateView(
            title: allEmpty ? L.string("tasks_tree_empty_title") : (searching ? L.string("search_no_matches_title") : L.string("tasks_tree_filtered_empty_title")),
            message: allEmpty
                ? L.string("tasks_tree_empty_body")
                : (searching
                    ? L.string("tasks_tree_search_empty_body")
                    : L.string("tasks_tree_filtered_empty_body"))
        )
    }

}

// MARK: - One row (with move-mode lift + the kind-aware command menu)

/// One tree row plus its right-click **command menu** (#231/#299) — the macOS twin of the Android
/// `DropdownMenu` (`ItemTreeUi.kt`). A dedicated view so it can own the two menu-spawned dialogs' `@State`
/// (the Add-subtask prompt + the Delete confirmation), which a `@ViewBuilder` func on the parent can't.
///
/// The menu is **kind-aware** (ADR-0034 decision 7): a Task row gets Open · Open in New Window · Add
/// subtask · Move · Undo move · Pin/Unpin · Add/Remove from plan · the working-state block (Start working /
/// Mark done / Set aside) · Delete; a recurring (non-Task) row gets the cross-kind subset Add subtask ·
/// Move · Undo move plus the **definition-state block** Activate / Send to review / Archive (#299). `Pin`,
/// plan, the working-state block and `Delete` stay Task-only (mirrors Android). Each handler computes its
/// target from the row's current value — the "args from the row" rule — since the tree row is a cross-kind
/// `Item` projection that may have no joined state. `isTask` is the shared bridge helper
/// (`BridgeKt.itemKindIsTask`); per-row status comes from the joined `menuState` (Task) or
/// `item.definitionState` (non-Task, `nil` for a Task).
///
/// This row is also the **entry point for the detached detail window** (#196, ADR-0033) — the `task-detail`
/// scene in `DefernoApp` and `TaskDetailWindowRoot.openTaskDetailWindow` were both live but unreachable
/// after the flat `TaskListView` that used to carry the trigger was subsumed by the tree (#227): the
/// context-menu item, the double-click accelerator and the VoiceOver rotor action below are that restored
/// trigger, all three routed through `openDetailWindow()` so they share one set of gates (#368).
private struct ItemRowContainer: View {
    let row: ItemRow
    let moveMode: MoveMode?
    /// The joined Task working-state/pinned/in-plan (#231) — `nil` for a non-Task row, OR a Task whose join
    /// hasn't loaded yet (the rows and the menu state are independent Flows).
    let menuState: TaskMenuState?
    let canUndo: Bool
    let component: ItemTreeComponent

    @Environment(\.defernoColors) private var colors
    /// Opens the `task-detail` scene declared in `DefernoApp` (#196, ADR-0033). Value-based (`String`, the
    /// raw item id), so re-opening an already-open task raises its existing window instead of duplicating it.
    @Environment(\.openWindow) private var openWindow

    /// The two menu-spawned dialogs (#231): the destructive Delete confirm and the Add-subtask title prompt.
    @State private var confirmDelete = false
    @State private var addSubtaskOpen = false
    @State private var newSubtaskTitle = ""

    private var inMoveMode: Bool { moveMode != nil }
    private var isLifted: Bool { moveMode?.liftedId == row.item.id }
    private var isTask: Bool { BridgeKt.itemKindIsTask(kind: row.item.kind) }

    /// The single detached-window trigger (#196, ADR-0033), shared by all three entry points — the context
    /// menu item, the double-click accelerator and the VoiceOver rotor action — so the gates can't drift
    /// apart between them: Task rows only (nothing else has a detail surface) and never mid-move (the move
    /// bar owns the surface). The `openWindow` payload is the raw item id, which `openTaskDetailWindow`
    /// re-wraps as a `TaskId` over the live account session, so the window shares this shell's SQLite
    /// driver and edits sync between them.
    private func openDetailWindow() {
        guard isTask, !inMoveMode else { return }
        openWindow(id: "task-detail", value: row.item.id)
    }

    var body: some View {
        ItemRowView(
            row: row,
            onToggleExpand: { id, expanded in
                guard !inMoveMode else { return } // inert during a move — only the move bar acts
                component.onToggleExpand(id: id, currentlyExpanded: expanded)
            },
            onOpenDetail: { id, kind in
                guard !inMoveMode else { return }
                component.onOpenDetail(id: id, kind: kind)
            }
        )
        // The lifted row is highlighted; the rest of the list calms (dimmed) while a move is in progress.
        .background(isLifted ? colors.primaryContainer : Color.clear)
        .opacity(inMoveMode && !isLifted ? 0.38 : 1)
        .contentShape(Rectangle())
        // macOS move-mode / command-menu entry: a right-click context menu (the native desktop idiom for
        // "more actions"), in place of iOS's touch long-press. Empty in move mode so a mid-move right-click
        // is a no-op (the move bar owns the surface).
        .contextMenu { rowMenu() }
        // Double-click → detached detail window (#196, ADR-0033) — the Finder/Mail accelerator for the
        // context menu's "Open in New Window", and the reason the `task-detail` scene is value-based.
        // `simultaneousGesture` (not `.gesture`) because `ItemRowView`'s own body tap-to-fold is a CHILD
        // gesture and would otherwise win outright.
        //
        // The price of simultaneous recognition is that BOTH clicks also reach whatever child control sits
        // under the pointer: two folds when the pointer is over the title region, two `onOpenDetail` calls
        // when it is over the trailing ›. Neither is destructive — both are idempotent *destinations*, and
        // the second `onOpenDetail` re-selects the task the new window is showing anyway. But do not read
        // the double fold as a guaranteed no-op: both calls pass the `row.isExpanded` this row was rendered
        // with, so if SwiftUI has not re-rendered between the two clicks they send the same target twice
        // and the row nets a *change* rather than settling back. The real fix is scoping the gesture to the
        // title region, which needs a hook inside `ItemRowView` (Common/CommonViews.swift — shared with the
        // other tree surfaces) rather than a modifier out here, so it is deferred to #368 rather than
        // bodged from this side.
        //
        // Task rows only (nothing else has a detail surface) and never mid-move — see `openDetailWindow()`,
        // which owns those gates for all three triggers.
        .simultaneousGesture(TapGesture(count: 2).onEnded { openDetailWindow() })
        // The non-pointer equivalent of that accelerator (#368): a VoiceOver rotor action. The context-menu
        // item is the keyboard route, but VoiceOver reaches per-row commands through the rotor, not a
        // right-click, so without this "Open in New Window" was mouse-or-menu only. Gated identically to
        // the double-click, so on a non-Task row it is inert exactly as the double-click is.
        .accessibilityAction(named: Text(L.string("tasks_menu_open_in_new_window"))) { openDetailWindow() }
        // Delete confirm (destructive, #231) — mirrors the Task-detail kebab's confirm.
        .confirmationDialog(
            L.format("tasks_delete_item_confirm_title", row.item.title),
            isPresented: $confirmDelete,
            titleVisibility: .visible
        ) {
            Button(L.string("common_delete"), role: .destructive) { component.onDelete(id: row.item.id) }
            Button(L.string("common_cancel"), role: .cancel) {}
        } message: {
            Text(L.string("common_cannot_be_undone"))
        }
        // Add subtask (#231): a native title prompt — the tree has no inline add field (that's the detail's).
        // The child is always a Task (only Tasks carry a parent). A blank title is gated out (Add disabled).
        .alert(L.string("tasks_menu_add_subtask"), isPresented: $addSubtaskOpen) {
            TextField(L.string("new_title_label"), text: $newSubtaskTitle)
            Button(L.string("common_add")) {
                component.onAddSubtask(parentId: row.item.id, title: newSubtaskTitle)
            }
            .disabled(newSubtaskTitle.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            Button(L.string("common_cancel"), role: .cancel) {}
        } message: {
            Text(L.format("tasks_new_subtask_under_a11y", row.item.title))
        }
        .onChange(of: addSubtaskOpen) { _, open in
            if open { newSubtaskTitle = "" } // fresh prompt each time
        }
        // Nothing on a row announces that it carries a command menu, so VoiceOver users had no way to
        // learn the right-click/rotor commands exist (#368 G23). The macOS wording is its own key — the
        // iOS `tasks_row_long_press_hint` names a gesture this platform doesn't have. Empty in move mode,
        // where `rowMenu()` is deliberately empty, so the hint can't promise commands that aren't there.
        .accessibilityHint(inMoveMode ? "" : L.string("tasks_row_right_click_hint"))
    }

    @ViewBuilder
    private func rowMenu() -> some View {
        // Empty in move mode so a mid-move right-click is inert.
        if !inMoveMode {
            // Open routes to the Task-only detail surface (the other kinds have no detail yet).
            if isTask {
                Button { component.onOpenDetail(id: row.item.id, kind: row.item.kind) } label: {
                    Label(L.string("tasks_menu_open"), systemImage: "arrow.up.right.square")
                }
                // …and the macOS-only sibling: the same detail in its own detached, navigable window
                // (#196, ADR-0033) instead of the inline pane. Routed through `openDetailWindow()` — the
                // one place that owns the Task-only / not-mid-move gates — even though this branch is
                // already inside both, so the three triggers can never diverge.
                Button { openDetailWindow() } label: {
                    Label(L.string("tasks_menu_open_in_new_window"), systemImage: "macwindow.badge.plus")
                }
            }
            Button { addSubtaskOpen = true } label: {
                Label(L.string("tasks_menu_add_subtask"), systemImage: "plus")
            }
            Button { component.onEnterMoveMode(id: row.item.id) } label: {
                Label(L.string("tasks_menu_move"), systemImage: "arrow.up.arrow.down")
            }
            if canUndo {
                Button { component.undoLastMove() } label: {
                    Label(L.string("tasks_menu_undo_move"), systemImage: "arrow.uturn.backward")
                }
            }

            if isTask {
                // Pin / plan / the working-state block need the joined per-row state (label direction + which
                // verb to hide), so they appear once it's present; Delete needs only the id, so it rides the
                // kind gate alone.
                if let menu = menuState {
                    Divider()
                    Button { component.onSetPinned(id: row.item.id, pinned: !menu.pinned) } label: {
                        Label(menu.pinned ? L.string("tasks_menu_unpin") : L.string("tasks_menu_pin"),
                              systemImage: menu.pinned ? "pin.slash" : "pin")
                    }
                    Button { component.onSetInPlan(id: row.item.id, inPlan: !menu.inPlan) } label: {
                        Label(menu.inPlan ? L.string("tasks_menu_remove_from_plan") : L.string("tasks_menu_add_to_plan"),
                              systemImage: menu.inPlan ? "calendar.badge.minus" : "calendar.badge.plus")
                    }
                    Divider()
                    // The status block: each verb hidden when the Task is already in that state.
                    if menu.workingState != WorkingState.inProgress {
                        Button { component.onSetWorkingState(id: row.item.id, target: WorkingState.inProgress) } label: {
                            Label(L.string("tasks_menu_start_working"), systemImage: "play")
                        }
                    }
                    if menu.workingState != WorkingState.done {
                        Button { component.onSetWorkingState(id: row.item.id, target: WorkingState.done) } label: {
                            Label(L.string("tasks_menu_mark_done"), systemImage: "checkmark")
                        }
                    }
                    if menu.workingState != WorkingState.dropped {
                        Button(role: .destructive) {
                            component.onSetWorkingState(id: row.item.id, target: WorkingState.dropped)
                        } label: {
                            Label(L.string("tasks_set_aside"), systemImage: "xmark.circle")
                        }
                    }
                }
                Divider()
                Button(role: .destructive) { confirmDelete = true } label: {
                    Label(L.string("tasks_menu_delete_permanent"), systemImage: "trash")
                }
            } else if let definition = row.item.definitionState {
                // The non-Task definition-state block (#299): Activate / Send to review / Archive, hiding the
                // verb for the current state (mirrors the Task working-state block). The shared component
                // resolves the row's kind itself, so we pass only id + target.
                Divider()
                if definition != DefinitionState.active {
                    Button { component.onSetDefinitionState(id: row.item.id, target: DefinitionState.active) } label: {
                        Label(L.string("tasks_menu_activate"), systemImage: "tray.and.arrow.up")
                    }
                }
                if definition != DefinitionState.inReview {
                    Button { component.onSetDefinitionState(id: row.item.id, target: DefinitionState.inReview) } label: {
                        Label(L.string("tasks_menu_send_to_review"), systemImage: "eye")
                    }
                }
                if definition != DefinitionState.archived {
                    Button(role: .destructive) {
                        component.onSetDefinitionState(id: row.item.id, target: DefinitionState.archived)
                    } label: {
                        Label(L.string("tasks_menu_archive"), systemImage: "archivebox")
                    }
                }
            }
        }
    }
}

// MARK: - Move-mode bar

/// The contextual move-mode control (ADR-0034 decision 6, #228): **↑ ↓** reorder among siblings and
/// **‹ ›** outdent / indent, each acting live per press, plus **Done** to exit. An illegal direction is
/// greyed (the client-side guard, driven by `MoveMode`'s flags).
private struct MoveModeBar: View {
    let move: MoveMode
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onOutdent: () -> Void
    let onIndent: () -> Void
    let onDone: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(spacing: 4) {
            MoveControl(icon: .moveUp, label: L.string("tasks_move_up"), enabled: move.canMoveUp, action: onMoveUp)
            MoveControl(icon: .moveDown, label: L.string("tasks_move_down"), enabled: move.canMoveDown, action: onMoveDown)
            MoveControl(icon: .outdent, label: L.string("tasks_move_outdent"), enabled: move.canOutdent, action: onOutdent)
            MoveControl(icon: .indent, label: L.string("tasks_move_indent"), enabled: move.canIndent, action: onIndent)
            Spacer(minLength: 8)
            Button(action: onDone) {
                Text(L.string("calendar_action_done"))
                    .font(.body.weight(.semibold))
                    .foregroundStyle(colors.primary)
                    .frame(minHeight: Layout.minTouchTarget)
                    .padding(.horizontal, 12)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 8)
        .frame(maxWidth: .infinity)
        .background(colors.surfaceVariant)
        .overlay(alignment: .top) { Divider().background(colors.outlineVariant) }
    }
}

/// One direction control in the `MoveModeBar`: an icon button labelled for VoiceOver, greyed when illegal.
private struct MoveControl: View {
    let icon: DefernoIcon
    let label: String
    let enabled: Bool
    let action: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        Button(action: action) {
            icon.image(size: 18)
                .foregroundStyle(enabled ? colors.onSurface : colors.outlineVariant)
                .frame(width: Layout.minTouchTarget, height: Layout.minTouchTarget)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityLabel(label)
    }
}

// MARK: - Undo snackbar

/// A calm top-anchored snackbar offered after a move (ADR-0034 decision 8, #230): "Moved" + an **Undo**
/// action that reverts through `undoLastMove`. Auto-dismisses after a few seconds; re-arms whenever a new
/// move is recorded (keyed on `moveKey`).
private struct UndoSnackbar: View {
    let operation: String
    let moveKey: Int
    let onUndo: () -> Void
    @Environment(\.defernoColors) private var colors
    @State private var dismissed = false

    var body: some View {
        Group {
            if !dismissed {
                HStack(spacing: 12) {
                    Text(L.string("tasks_moved_snackbar"))
                        .font(.subheadline)
                        .foregroundStyle(colors.onSurface)
                    Spacer(minLength: 8)
                    Button {
                        dismissed = true
                        onUndo()
                    } label: {
                        HStack(spacing: 6) {
                            DefernoIcon.undo.image(size: 14)
                            Text(L.format("tasks_undo_operation_confirm_title", operation))
                        }
                        .font(.subheadline.weight(.semibold))
                        .foregroundStyle(colors.primary)
                        .frame(minHeight: Layout.minTouchTarget)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 4)
                .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
                .overlay(
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(colors.outlineVariant, lineWidth: 1)
                )
                .shadow(color: .black.opacity(0.08), radius: 6, y: 2)
                .accessibilityElement(children: .contain)
                .accessibilityLabel(L.format("tasks_moved_undo_available", operation))
            }
        }
        .onChange(of: moveKey) { dismissed = false; scheduleDismiss() }
        .onAppear { dismissed = false; scheduleDismiss() }
    }

    private func scheduleDismiss() {
        let key = moveKey
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            if key == moveKey { dismissed = true }
        }
    }
}

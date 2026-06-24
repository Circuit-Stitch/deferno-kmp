import Deferno
import SwiftUI

/// The Tasks Destination as one nested, collapsible **Item tree** across all four kinds (#227,
/// ADR-0034) — the cross-kind forest the old flat list + one-level drill pane were subsumed into. A
/// thin renderer of `ItemTreeComponent`: it observes the flattened `ItemTreeState.rows` and forwards
/// each row's toggle/open/refresh to the component, holding no logic of its own (ADR-0007).
///
/// Restyled to the "See the trees" direction (#231) + the modal move mode and undo of ADR-0034
/// decisions 6/8 (#228/#230), mirroring the Android `ItemTreeContent`. On iOS the header chrome is now
/// **native** (#263): the host (`TasksScreen`) supplies the large "Everything" nav title, a collapse-on-
/// scroll `.searchable` field, and the shell `.toolbar`, so this View renders only the in-list body:
///  - a slim `{n} trees` count + a local `SegmentedFilter` (In today / Active / All) as the first row;
///  - the forest rows + an "Add a tree" `DashedAddButton` footer;
///  - a **modal move mode** (long-press lift, ↑ ↓ ‹ › + Done) and a top **undo snackbar** after a move.
///
/// `query` is the native search text — an inline filter over the loaded forest; `onAdd` opens the New
/// create overlay. Both default so existing `init(component:)` call sites stay stable.
struct ItemTreeView: View {
    let component: ItemTreeComponent
    let onAdd: () -> Void
    /// The native `.searchable` text, threaded from the host's nav bar. Empty → no filtering; otherwise a
    /// flat, case-insensitive title match over the loaded forest (#263). Cross-everything search is the
    /// drawer's "Search" row, not this.
    let query: String
    /// Render an in-list "Everything" headline above the count. Only the **regular/iPad** two-pane column
    /// sets this: there the nav title is the whole-screen "Tasks", so the 340pt column would otherwise be
    /// anonymous. Compact leaves it false — its large nav title already says "Everything" (#263).
    let showsColumnTitle: Bool
    @StateObject private var state: StateFlowObserver<ItemTreeState>
    @Environment(\.defernoColors) private var colors

    /// Local in-list filter (#231), client-side over `state.rows` by working state. Defaults to
    /// **All** so the tree's existing behaviour is unchanged — the filter is an opt-in narrowing.
    /// Mapping (mirrors Android `ItemTreeContent`): `Item` carries only `isTerminal` (no working
    /// state on the cross-kind projection yet), so:
    ///  - **In today** (0) → non-terminal rows (plan membership isn't on `Item` yet, so this is the
    ///    closest calm narrowing);
    ///  - **Active** (1) → non-terminal rows (in-progress / in-review / open all read as non-terminal);
    ///  - **All** (2) → everything (terminal rows still show, de-emphasized).
    @State private var filterIndex: Int = 2

    private static let filters = ["In today", "Active", "All"]

    init(
        component: ItemTreeComponent,
        onAdd: @escaping () -> Void = {},
        query: String = "",
        showsColumnTitle: Bool = false
    ) {
        self.component = component
        self.onAdd = onAdd
        self.query = query
        self.showsColumnTitle = showsColumnTitle
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        let inMoveMode = value.moveMode != nil
        let visibleRows = filteredRows(value.rows)
        let treeCount = value.rows.filter { $0.depth == 0 }.count

        ZStack(alignment: .top) {
            VStack(spacing: 0) {
                List {
                    // A slim count + the local filter as the first row; scrolls away with the list, hidden in
                    // move mode (the lifted-row focus owns the surface). The title + search are the native bar.
                    if !inMoveMode {
                        metaFilterBar(treeCount: treeCount)
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }

                    if value.isRefreshing {
                        LoadingStrip(label: "Refreshing…")
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
                            self.row(row, moveMode: value.moveMode)
                                .listRowInsets(EdgeInsets())
                                .listRowBackground(Color.clear)
                        }
                    }

                    // "Add a tree" footer (#231) — only outside move mode (the list is calm during a move).
                    if !inMoveMode {
                        DashedAddButton(title: "Add a tree", action: onAdd)
                            .padding(.horizontal, Layout.gutter)
                            .padding(.vertical, 12)
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }
                }
                .listStyle(.plain)
                // The List otherwise paints its own systemBackground (white) over the screen's beige
                // colors.background; hide it (+ clear row cells below) so the warm surface shows through.
                .scrollContentBackground(.hidden)
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
                    operation: undo.operation,
                    // Re-arm the auto-dismiss whenever a *new* move is recorded (key on the move id).
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
        .background(colors.background)
    }

    // MARK: - In-list header (count + filter)

    /// The `{n} trees` count + the local In today / Active / All filter — the first list row. The
    /// "Everything" title, search field, and create actions are the native nav bar (`TasksScreen`).
    @ViewBuilder
    private func metaFilterBar(treeCount: Int) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            // iPad column identity — compact gets this from the large nav title instead.
            if showsColumnTitle {
                Text("Everything")
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(colors.onSurface)
            }
            MonoMeta(treeCount == 1 ? "1 tree" : "\(treeCount) trees")
            SegmentedFilter(
                options: Self.filters,
                selectedIndex: filterIndex,
                onSelect: { filterIndex = $0 }
            )
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.top, 8)
        .padding(.bottom, 8)
        .background(colors.surface)
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
    /// everything (switch to All) — the prior copy misdirected by always saying "clear the search".
    @ViewBuilder
    private func emptyState(allEmpty: Bool, searching: Bool) -> some View {
        EmptyStateView(
            title: allEmpty ? "No trees yet" : (searching ? "No matches" : "Nothing to show here"),
            message: allEmpty
                ? "When you add a tree, it shows up here. One small step at a time."
                : (searching
                    ? "No tree matches your search. Clear it to see them all."
                    : "Everything here is done. Switch to “All” to see it again.")
        )
    }

    // MARK: - One row (with move-mode lift + highlight)

    @ViewBuilder
    private func row(_ row: ItemRow, moveMode: MoveMode?) -> some View {
        let inMoveMode = moveMode != nil
        let isLifted = moveMode?.liftedId == row.item.id

        ItemRowView(
            row: row,
            onToggleExpand: { id, expanded in
                // Inert during a move (the list is calm — only the move bar acts).
                guard !inMoveMode else { return }
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
        // A long-press lifts the row into move mode (ADR-0034 decision 6, #228). Disabled while already
        // in move mode so a stray long-press can't re-lift mid-move.
        .onLongPressGesture(minimumDuration: 0.4) {
            guard !inMoveMode else { return }
            component.onEnterMoveMode(id: row.item.id)
        }
        .accessibilityHint(inMoveMode ? "" : "Long press to move")
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
            MoveControl(icon: .moveUp, label: "Move up", enabled: move.canMoveUp, action: onMoveUp)
            MoveControl(icon: .moveDown, label: "Move down", enabled: move.canMoveDown, action: onMoveDown)
            MoveControl(icon: .outdent, label: "Outdent", enabled: move.canOutdent, action: onOutdent)
            MoveControl(icon: .indent, label: "Indent", enabled: move.canIndent, action: onIndent)
            Spacer(minLength: 8)
            Button(action: onDone) {
                Text("Done")
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
            icon.image(size: 20)
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
/// move is recorded (keyed on `moveKey`). The state stays authoritative — this only offers the action; the
/// snackbar simply hides itself locally once shown so it doesn't linger.
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
                    Text("Moved")
                        .font(.subheadline)
                        .foregroundStyle(colors.onSurface)
                    Spacer(minLength: 8)
                    Button {
                        dismissed = true
                        onUndo()
                    } label: {
                        HStack(spacing: 6) {
                            DefernoIcon.undo.image(size: 14)
                            Text("Undo \(operation)")
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
                .accessibilityLabel("Moved. Undo \(operation) available.")
            }
        }
        // Re-arm whenever a new move is recorded; auto-dismiss after a calm interval.
        .onChange(of: moveKey) { _ in dismissed = false; scheduleDismiss() }
        .onAppear { dismissed = false; scheduleDismiss() }
    }

    private func scheduleDismiss() {
        let key = moveKey
        DispatchQueue.main.asyncAfter(deadline: .now() + 4) {
            // Only dismiss if no newer move re-armed us (key unchanged).
            if key == moveKey { dismissed = true }
        }
    }
}

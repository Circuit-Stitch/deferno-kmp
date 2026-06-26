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
/// ponytail: keyboard move (Alt+↑↓ / Tab) is out of #237's "buttons + undo" scope; the move math already
/// lives in the shared component, so add the key handlers here when the desktop keyboard pass lands.
struct ItemTreeView: View {
    let component: ItemTreeComponent
    @StateObject private var state: StateFlowObserver<ItemTreeState>
    @Environment(\.defernoColors) private var colors

    /// Local in-list filter (#231), client-side over `state.rows` by terminal state. Defaults to **All**
    /// so the tree's existing behaviour is unchanged — the filter is an opt-in narrowing. `Item` carries
    /// only `isTerminal` (no working state on the cross-kind projection yet), so In today / Active both map
    /// to "non-terminal" and All shows everything (terminal rows de-emphasized).
    @State private var filterIndex: Int = 2

    private static let filters = ["In today", "Active", "All"]

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
                        LoadingStrip(label: "Refreshing…")
                            .listRowInsets(EdgeInsets())
                            .listRowSeparator(.hidden)
                            .listRowBackground(Color.clear)
                    }

                    if visibleRows.isEmpty && !value.isRefreshing {
                        emptyState(allEmpty: value.rows.isEmpty)
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
                    operation: undo.operation,
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

    @ViewBuilder
    private func metaFilterBar(treeCount: Int, showBlocked: Bool) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            MonoMeta(treeCount == 1 ? "1 tree" : "\(treeCount) trees")
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
                    label: "Show blocked",
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
        // In today / Active → non-terminal only; All → everything.
        // ponytail: filtering a terminal parent can leave a child's rail rooted at a hidden node — the same
        // minor imperfection iOS accepts; far better than re-deriving the spine. Upgrade only if it reads wrong.
        switch filterIndex {
        case 0, 1: return rows.filter { !$0.item.isTerminal }
        default: return rows
        }
    }

    @ViewBuilder
    private func emptyState(allEmpty: Bool) -> some View {
        EmptyStateView(
            title: allEmpty ? "No trees yet" : "Nothing to show here",
            message: allEmpty
                ? "When you add a tree, it shows up here. One small step at a time."
                : "Everything here is done. Switch to “All” to see it again."
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
        // macOS move-mode entry: a right-click context menu (the native desktop idiom for "more actions"),
        // in place of iOS's touch long-press. Empty in move mode so a mid-move right-click is a no-op.
        .contextMenu { rowMenu(for: row, inMoveMode: inMoveMode) }
    }

    @ViewBuilder
    private func rowMenu(for row: ItemRow, inMoveMode: Bool) -> some View {
        if !inMoveMode {
            Button { component.onEnterMoveMode(id: row.item.id) } label: {
                Label("Move", systemImage: "arrow.up.arrow.down")
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

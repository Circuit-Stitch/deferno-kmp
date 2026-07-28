import Deferno
import SwiftUI

/// The **Activity** Destination View (#260) — a calm, read-only, reverse-chronological feed of every change
/// the app has recorded in the offline-first ledger. The macOS twin of the iOS `ActivityView`; mirrors the
/// Compose `ActivityScreen` (app/shell/ui). A thin render of `ActivityComponent` (its `state` observed via
/// SKIE): each row shows what changed, who changed it, and when it was applied. Rows arrive newest-first;
/// there are no row actions. Server-sourced rows ("via Website" / "via MCP") land here too once the
/// reconcile seam tags them, with no View change.
struct ActivityView: View {
    let component: ActivityComponent
    @Environment(\.defernoColors) private var colors
    @StateObject private var state: StateFlowObserver<ActivityFeedState>
    /// The clicked row's change-detail sheet — the same `ChangeDiffSheet` the Task Trail opens (#260).
    @State private var selected: ActivityDetail?

    init(component: ActivityComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let rows = state.value.rows
        VStack(spacing: 0) {
            countBand(count: rows.count)
            if rows.isEmpty {
                EmptyStateView(
                    title: L.string("activity_empty_title"),
                    message: L.string("activity_empty_body")
                )
            } else {
                List {
                    ForEach(rows, id: \.seq) { row in
                        Button { selected = ActivityDetail(row: row) } label: {
                            ActivityRowView(row: row)
                        }
                        // Load-bearing on macOS, not cosmetic: the default button style tints a Button's
                        // *title* with the accent, which would wash the row's `onSurface`/`inkMuted` ink
                        // out — the same defect that forced `SelectableChip` into existence.
                        .buttonStyle(.plain)
                        // One spoken element per row: the badge and the mono meta are decoration around a
                        // single sentence, and VoiceOver shouldn't stop on each. Matches `SearchHitRow`.
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel(rowAccessibilityLabel(row))
                        // Desktop density: 8pt vertical insets against the iOS twin's 12 (and `Layout.gutter`
                        // is 10 here vs 16 there). A pointer hits the tighter row just fine.
                        .listRowInsets(EdgeInsets(top: 8, leading: Layout.gutter, bottom: 8, trailing: Layout.gutter))
                        .listRowBackground(colors.background)
                    }
                }
                .listStyle(.plain)
                // AppKit's List paints its own inset background over the pane's, so hide it (the idiom
                // `ItemTreeView` already uses) and let `colors.background` below show through.
                .scrollContentBackground(.hidden)
            }
        }
        .background(colors.background)
        // Clicking a row opens the shared change-detail sheet: the old→new field diff, the comment text
        // (if any), and "Open Task #N" for rows that resolve to a Task. Plain `.sheet(item:)` — sheet
        // content inherits the presenting pane's environment, so no `ThemedSheet` wrapper is needed (that
        // one exists for the sheets hung off the un-themed window root).
        .sheet(item: $selected) { detail in
            let row = detail.row
            ChangeDiffSheet(
                title: L.activitySummary(row),
                // The "when" half comes from the Kotlin seam rather than `TrailDateFormat.whenLabel`, so
                // this reads "2026-07-28 14:03" where the Trail's sheet reads "Jul 28 · 14:03". Deliberate:
                // the seam is what iOS renders, and unifying would need a new epoch-seconds bridge function
                // (Kotlin is frozen for this PR) *and* would then diverge from the iOS twin instead.
                subtitle: "\(L.activitySource(row)) · \(ShellBridgeKt.activityWhenLabel(row: row))",
                rows: ShellBridgeKt.activityRowDiffRows(row: row),
                note: row.commentBody,
                // `selected = nil` retires the sheet binding before the shell laterally switches to Tasks —
                // the sheet's own Done/Escape only calls `dismiss()`, which never clears this state.
                onOpenItem: row.itemId.map { id in { component.openItem(id: id); selected = nil } },
                openItemLabel: openItemLabel(for: row)
            )
        }
    }

    /// "Open Task #41" only when the row resolves to a Task with a ref — a Habit/Chore/Event would deep-link
    /// wrong, so those keep the generic "Open item" (Compose parity, `ActivityFeed.kt`).
    private func openItemLabel(for row: ActivityFeedRow) -> String? {
        guard ShellBridgeKt.activityRowIsTask(row: row), let ref = row.itemRef else { return nil }
        return L.format("common_open_named_cd", "\(L.string("common_kind_task")) \(ref)")
    }

    /// The whole row as one sentence: what changed, the comment snippet (when there is one), who, and when.
    private func rowAccessibilityLabel(_ row: ActivityFeedRow) -> String {
        var parts = [L.activitySummary(row)]
        if let body = row.commentBody { parts.append(body) }
        parts.append(L.activitySource(row))
        parts.append(ShellBridgeKt.activityWhenLabel(row: row))
        return parts.joined(separator: ", ")
    }

    /// The count band — "17 changes". Deliberately *without* the iOS twin's 22pt "Activity" title: the macOS
    /// shell already names the foreground surface (the window title bar in the regular layout, the top bar in
    /// compact — `MainShellView.barTitle`), so repeating it here would title the pane twice. The Compose
    /// `ActivityScreen` made the same call for the same reason, and every other macOS Destination pane omits
    /// its own name (see `PlanView`'s "No PaneHeader" note).
    private func countBand(count: Int) -> some View {
        MonoMeta(L.plural("activity_change_count", count))
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 8)
            .background(colors.surface)
    }
}

/// One feed row: the change summary, a source badge, and the absolute time it was applied. Read-only.
private struct ActivityRowView: View {
    let row: ActivityFeedRow
    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L.activitySummary(row))
                    .font(.defernoMono(15))
                    .foregroundStyle(colors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                // Badge and comment snippet share a line (the Compose row's shape) rather than stacking as
                // on iOS — one saved line per row across a long feed, which is the desktop density this app
                // is tuned to.
                HStack(spacing: 8) {
                    // macOS has no `TreeChip`, by design — `DependencyBadge` is its documented substitute
                    // (the closing note in DesignSystem/Atoms.swift): the same mono badge, but with a
                    // REQUIRED `semanticLabel` so the uppercased glyphs are never what VoiceOver reads.
                    DependencyBadge(
                        text: L.activitySource(row),
                        tone: .neutral,
                        semanticLabel: L.activitySource(row)
                    )
                    // A comment row carries its text here (the SwiftUI twin of the Compose sub-line snippet).
                    if let body = row.commentBody {
                        Text(body)
                            .font(.defernoMono(13))
                            .foregroundStyle(colors.inkMuted)
                            .lineLimit(1)
                            .truncationMode(.tail)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            MonoMeta(ShellBridgeKt.activityWhenLabel(row: row))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// A stable-identity wrapper so a clicked `ActivityFeedRow` can drive `.sheet(item:)` — the Kotlin row has
/// no `Identifiable`; its `seq` is the ledger's monotonic key. Mirrors `DiffPresentation` in TaskDetailView.
private struct ActivityDetail: Identifiable {
    let id: Int64
    let row: ActivityFeedRow
    init(row: ActivityFeedRow) {
        self.id = row.seq
        self.row = row
    }
}

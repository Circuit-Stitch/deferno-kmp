import Deferno
import SwiftUI

/// The **Activity** Destination View — a calm, read-only, reverse-chronological feed of every change
/// the app has recorded in the offline-first ledger (mirrors the Android `ActivityScreen`). A thin
/// render of `ActivityComponent` (its `state` observed via SKIE): each row shows what changed,
/// a small source chip, and when it was applied. Rows arrive newest-first; there are no row actions.
/// Server-sourced rows ("via Website" / "via MCP") land here too once the reconcile seam tags them,
/// with no View change.
struct ActivityView: View {
    let component: ActivityComponent
    @Environment(\.defernoColors) private var colors
    @StateObject private var state: StateFlowObserver<ActivityFeedState>
    // The tapped row's change-detail sheet (parity with Android's ActivityScreen ChangeDiffSheet, #260).
    @State private var selected: ActivityDetail?

    init(component: ActivityComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let rows = state.value.rows
        VStack(spacing: 0) {
            header(count: rows.count)
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
                        .buttonStyle(.plain)
                        .listRowInsets(EdgeInsets(top: 12, leading: Layout.gutter, bottom: 12, trailing: Layout.gutter))
                        .listRowBackground(colors.background)
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(colors.background)
        // Tapping a row opens the shared change-detail sheet (Android parity): the old→new field diff, the
        // comment text (if any), and "Open Task #N" for rows that resolve to a Task.
        .sheet(item: $selected) { detail in
            let row = detail.row
            ChangeDiffSheet(
                title: L.activitySummary(row),
                subtitle: "\(L.activitySource(row)) · \(ShellBridgeKt.activityWhenLabel(row: row))",
                rows: ShellBridgeKt.activityRowDiffRows(row: row),
                note: row.commentBody,
                onOpenItem: row.itemId.map { id in { component.openItem(id: id); selected = nil } },
                openItemLabel: openItemLabel(for: row)
            )
        }
    }

    /// "Open Task #41" only when the row resolves to a Task with a ref — a Habit/Chore/Event would deep-link
    /// wrong, so those keep the generic "Open item" (Android parity, ActivityFeed.kt).
    private func openItemLabel(for row: ActivityFeedRow) -> String? {
        guard ShellBridgeKt.activityRowIsTask(row: row), let ref = row.itemRef else { return nil }
        return L.format("common_open_named_cd", "\(L.string("common_kind_task")) \(ref)")
    }

    private func header(count: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(L.string("shell_destination_activity"))
                .font(.defernoMono(22, weight: .semibold))
                .foregroundColor(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            MonoMeta(L.plural("activity_change_count", count))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 8)
        .background(colors.surface)
    }
}

/// One feed row: the change summary, a source chip, and the absolute time it was applied. Read-only.
private struct ActivityRowView: View {
    let row: ActivityFeedRow
    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(alignment: .center, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                Text(L.activitySummary(row))
                    .font(.defernoMono(15))
                    .foregroundColor(colors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                // A comment row carries its text here (the SwiftUI twin of the Compose sub-line snippet).
                if let body = row.commentBody {
                    Text(body)
                        .font(.defernoMono(13))
                        .foregroundColor(colors.inkMuted)
                        .lineLimit(1)
                        .truncationMode(.tail)
                }
                TreeChip(text: L.activitySource(row), tone: .neutral)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            MonoMeta(ShellBridgeKt.activityWhenLabel(row: row))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

/// A stable-identity wrapper so a tapped `ActivityFeedRow` can drive `.sheet(item:)` — the Kotlin row has no
/// `Identifiable`; its `seq` is the ledger's monotonic key. Mirrors `DiffPresentation` in TaskDetailView.
private struct ActivityDetail: Identifiable {
    let id: Int64
    let row: ActivityFeedRow
    init(row: ActivityFeedRow) {
        self.id = row.seq
        self.row = row
    }
}

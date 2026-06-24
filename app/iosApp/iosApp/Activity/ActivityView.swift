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
                    title: "Nothing yet",
                    message: "Every change you make — and every change synced from elsewhere — shows up here, newest first."
                )
            } else {
                List {
                    ForEach(rows, id: \.seq) { row in
                        ActivityRowView(row: row)
                            .listRowInsets(EdgeInsets(top: 12, leading: Layout.gutter, bottom: 12, trailing: Layout.gutter))
                            .listRowBackground(colors.background)
                    }
                }
                .listStyle(.plain)
            }
        }
        .background(colors.background)
    }

    private func header(count: Int) -> some View {
        VStack(alignment: .leading, spacing: 2) {
            Text("Activity")
                .font(.defernoMono(22, weight: .semibold))
                .foregroundColor(colors.onSurface)
                .accessibilityAddTraits(.isHeader)
            MonoMeta(count == 1 ? "1 change" : "\(count) changes")
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
                Text(row.summary)
                    .font(.defernoMono(15))
                    .foregroundColor(colors.onSurface)
                    .fixedSize(horizontal: false, vertical: true)
                TreeChip(text: row.sourceLabel, tone: .neutral)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            MonoMeta(ShellBridgeKt.activityWhenLabel(row: row))
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

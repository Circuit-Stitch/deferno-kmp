import Deferno
import SwiftUI

/// The **change-detail bottom sheet** (#260) shared by the Task **Trail** and the **Activity** destination —
/// the SwiftUI twin of the Compose `ChangeDiffSheet` (core/designsystem), which both Android surfaces reuse.
/// A calm old→new field diff of one recorded change: [title] heads it, [subtitle] carries the meta line
/// (source · time), [note] — when set — is comment prose shown above the diff (a "Commented on …" row has no
/// field diff), and [onOpenItem] — when set — adds an "Open item" action ([openItemLabel] overrides its text,
/// e.g. "Open Task #99"); omitted when the viewer is already inside the item (the Trail). When there's
/// neither a note nor any rows a quiet fallback keeps a tap from being a dead end.
///
/// `.presentationDetents([.medium, .large])` + the drag indicator give Android's `ModalBottomSheet` feel: a
/// draggable, partial-height panel that expands to full. Presentational only — each call site resolves the
/// strings + diff rows from its own model (the Trail from an `ActivityItem`, Activity from an `ActivityFeedRow`).
struct ChangeDiffSheet: View {
    let title: String
    var subtitle: String? = nil
    let rows: [TrailDiffRow]
    var note: String? = nil
    var onOpenItem: (() -> Void)? = nil
    var openItemLabel: String? = nil
    @Environment(\.defernoColors) private var colors

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 18) {
                VStack(alignment: .leading, spacing: 4) {
                    Text(title)
                        .font(.title3)
                        .accessibilityAddTraits(.isHeader)
                    if let subtitle { MonoMeta(subtitle) }
                }
                // A comment's own text: primary prose, selectable, shown above any field diff.
                if let note {
                    Text(note)
                        .font(.body)
                        .foregroundStyle(colors.onSurface)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                if rows.isEmpty {
                    // Only a genuine dead end (no note, no field diff) shows the fallback.
                    if note == nil {
                        Text(L.string("activity_diff_empty"))
                            .font(.subheadline)
                            .foregroundStyle(colors.inkMuted)
                    }
                } else {
                    ForEach(Array(rows.enumerated()), id: \.offset) { _, row in
                        DiffRowView(row: row)
                    }
                }
                if let onOpenItem {
                    Button(action: onOpenItem) {
                        Text(openItemLabel ?? L.string("activity_diff_open_item"))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                    .controlSize(.large)
                    .padding(.top, 6)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.top, 24)
            .padding(.horizontal, 22)
            .padding(.bottom, 28)
        }
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// One old→new field diff row: the field label, a struck-through "−" before value (muted, struck only when a
/// real value), then a "→" after value (in ink). Mirrors the Compose `DiffRowView`.
struct DiffRowView: View {
    let row: TrailDiffRow
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(L.diffFieldLabel(row.fieldToken).uppercased())
                .font(.caption.weight(.semibold))
                .foregroundStyle(colors.inkMuted)
            HStack(alignment: .top, spacing: 8) {
                Text("−").font(.subheadline).foregroundStyle(colors.inkMuted)
                Text(L.diffValueText(fieldToken: row.fieldToken, side: row.before))
                    .font(.subheadline)
                    .foregroundStyle(colors.inkMuted)
                    .strikethrough(row.before.kind == "PRESENT")
            }
            HStack(alignment: .top, spacing: 8) {
                Text("→").font(.subheadline).foregroundStyle(colors.primary)
                Text(L.diffValueText(fieldToken: row.fieldToken, side: row.after))
                    .font(.subheadline)
                    .foregroundStyle(colors.onSurface)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

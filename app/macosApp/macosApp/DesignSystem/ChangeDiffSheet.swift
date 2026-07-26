import Deferno
import SwiftUI

/// The **change-detail sheet** (#260) opened from the Task **Trail** — the macOS twin of the iOS
/// `ChangeDiffSheet` and of the Compose `ChangeDiffSheet` (core/designsystem). A calm old→new field diff of
/// one recorded change: [title] heads it, [subtitle] carries the meta line (time), [note] — when set — is
/// comment prose shown above the diff, and [onOpenItem] — when set — adds an "Open item" action
/// ([openItemLabel] overrides its text); omitted when the viewer is already inside the item (the Trail).
/// When there's neither a note nor any rows a quiet fallback keeps a click from being a dead end.
///
/// **macOS divergence from the iOS twin.** iOS sizes this with `.presentationDetents([.medium, .large])` +
/// a drag indicator. Those modifiers *compile* on macOS but are inert — AppKit sheets have no detents and
/// no drag-to-dismiss — so a verbatim port would size to its unconstrained content with no way out. This
/// sheet is therefore explicitly framed and carries a real **Done** button, which doubles as the
/// `.cancelAction` so Escape dismisses too (the house idiom: `StatusPickerSheet`, `DraftExtractorView`).
/// It also paints its own background: a macOS sheet does not inherit the presenting pane's.
struct ChangeDiffSheet: View {
    let title: String
    var subtitle: String? = nil
    let rows: [TrailDiffRow]
    var note: String? = nil
    var onOpenItem: (() -> Void)? = nil
    var openItemLabel: String? = nil
    @Environment(\.dismiss) private var dismiss
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
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
                        .padding(.top, 4)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            Divider()
            HStack {
                Spacer()
                // `common_done` ("Fertig"/"Listo"), NOT `calendar_action_done` — the latter is the
                // imperative "complete the task" ("Erledigen"/"Completar"), which on a dismiss button
                // would read as marking the item done. Both are "Done" in English only.
                Button(L.string("common_done")) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .frame(minWidth: 420, idealWidth: 460, minHeight: 300, idealHeight: 440)
        .background(colors.background)
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
                Text("−")
                    .font(.subheadline)
                    .foregroundStyle(colors.inkMuted)
                    .accessibilityHidden(true)
                Text(L.diffValueText(fieldToken: row.fieldToken, side: row.before))
                    .font(.subheadline)
                    .foregroundStyle(colors.inkMuted)
                    .strikethrough(row.before.kind == "PRESENT")
                    .textSelection(.enabled)
            }
            HStack(alignment: .top, spacing: 8) {
                Text("→")
                    .font(.subheadline)
                    .foregroundStyle(colors.primary)
                    .accessibilityHidden(true)
                Text(L.diffValueText(fieldToken: row.fieldToken, side: row.after))
                    .font(.subheadline)
                    .foregroundStyle(colors.onSurface)
                    .textSelection(.enabled)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

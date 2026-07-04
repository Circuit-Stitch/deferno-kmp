import Deferno
import SwiftUI

/// The Inbox Destination (ADR-0015 Inbox amendment) — the triage queue for brain-dump drafts, restyled to
/// the "See the trees" direction (iOS twin of the Android `InboxScreen`/`InboxUi`). A thin renderer of
/// `InboxComponent`: it observes the Ready drafts and, for each, offers **Accept** (commit it as a task,
/// online-only per ADR-0016) and a calm **Dismiss** (recoverable — nothing is deleted). While a create is
/// in flight the row yields its actions to a progress strip; any offline/error `note` shows gently with a
/// clear affordance; and a dismiss surfaces an **Undo** banner. An empty inbox is normal, not broken
/// (the calm "Inbox zero" empty state).
///
/// No NavigationStack/title here: the single adaptive shell bar (`MainShellView`) titles "Inbox".
struct InboxView: View {
    let component: InboxComponent
    @StateObject private var state: StateFlowObserver<InboxState>
    @Environment(\.defernoColors) private var colors

    init(component: InboxComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            if value.rows.isEmpty {
                EmptyStateView(
                    title: L.string("inbox_empty_title"),
                    message: L.string("inbox_empty_body")
                )
            } else {
                draftList(value)
            }
            if let dismissed = value.recentlyDismissed {
                undoBanner(dismissed)
            }
        }
        .background(colors.background)
    }

    // MARK: - List

    private func draftList(_ value: InboxState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                header(count: value.rows.count)
                SectionLabel(L.string("inbox_section_waiting"))
                    .padding(.horizontal, 20)
                ForEach(value.rows, id: \.bridgeKey) { row in
                    DraftCard(
                        draft: row.draft,
                        accepting: row.accepting,
                        note: L.inboxNote(row),
                        deadlineLabel: ShellBridgeKt.inboxDraftDeadlineLabel(draft: row.draft),
                        onAccept: { ShellBridgeKt.acceptInboxDraft(component: component, draft: row.draft) },
                        onDismiss: { ShellBridgeKt.dismissInboxDraft(component: component, draft: row.draft) },
                        onClearNote: { ShellBridgeKt.clearInboxNote(component: component, draft: row.draft) }
                    )
                    .padding(.horizontal, 20)
                }
                footer
            }
            .padding(.vertical, 12)
        }
    }

    private func header(count: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline) {
                Text(L.string("shell_destination_inbox"))
                    .font(.title2.weight(.semibold))
                    .foregroundStyle(colors.onSurface)
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 12)
                MonoMeta(draftCount(count))
            }
            Text(L.string("inbox_header_subtitle"))
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 8)
    }

    private var footer: some View {
        Text(L.string("inbox_footer_reassurance"))
            .font(.footnote)
            .foregroundStyle(colors.inkMuted)
            .frame(maxWidth: .infinity)
            .multilineTextAlignment(.center)
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
    }

    // MARK: - Undo banner

    private func undoBanner(_ dismissed: BrainDumpDraft) -> some View {
        HStack(spacing: 12) {
            Text(L.format("inbox_dismissed_snackbar", dismissed.title))
                .font(.subheadline)
                .foregroundStyle(colors.onSurface)
                .lineLimit(1)
            Spacer(minLength: 8)
            TextLink(title: L.string("common_undo")) { component.onUndoDismiss() }
        }
        .padding(.horizontal, 20)
        .padding(.vertical, 12)
        .frame(minHeight: Layout.minTouchTarget)
        .background(colors.secondaryContainer)
        .accessibilityElement(children: .combine)
    }

    /// "1 draft" / "N drafts" — the quiet count beside the header.
    private func draftCount(_ n: Int) -> String { L.plural("inbox_draft_count", n) }
}

private extension InboxRow {
    /// Stable String identity for SwiftUI list diffing — keyed off the draft's bridge key (an
    /// `InboxRow` is a Kotlin data class, so this is its `\.self` substitute).
    var bridgeKey: String { ShellBridgeKt.inboxDraftKey(draft: draft) }
}

/// One reviewable draft as a "See the trees" card: a `DRAFTED` eyebrow with a quiet **Dismiss** in the
/// corner, the title, an optional "Due …" mono line, the dictated notes, any gentle offline/error note
/// (with a Clear affordance), and a thumb-reachable **Accept** primary action. While the create is in
/// flight (`accepting`) the action yields to a progress strip and Dismiss hides (taps are ignored).
private struct DraftCard: View {
    let draft: BrainDumpDraft
    let accepting: Bool
    let note: String?
    let deadlineLabel: String
    let onAccept: () -> Void
    let onDismiss: () -> Void
    let onClearNote: () -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Eyebrow(L.string("inbox_draft_eyebrow"))
                Spacer(minLength: 8)
                if !accepting {
                    TextLink(title: L.string("common_dismiss"), action: onDismiss)
                }
            }
            Spacer().frame(height: 4)
            Text(draft.title)
                .font(.headline)
                .foregroundStyle(colors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            if !deadlineLabel.isEmpty {
                Spacer().frame(height: 6)
                MonoMeta(deadlineLabel)
            }
            if let notes = draft.notes, !notes.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                Spacer().frame(height: 8)
                Text(notes)
                    .font(.subheadline)
                    .foregroundStyle(colors.onSurfaceVariant)
                    .lineLimit(3)
            }
            if let note, !note.isEmpty {
                Spacer().frame(height: 8)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(colors.error)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer(minLength: 8)
                    TextLink(title: L.string("common_clear"), action: onClearNote)
                }
            }
            Spacer().frame(height: 14)
            if accepting {
                LoadingStrip(label: L.string("inbox_adding_task"))
            } else {
                PrimaryActionButton(title: L.string("inbox_accept_button"), icon: .check, action: onAccept)
            }
        }
        .padding(16)
        .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(
            RoundedRectangle(cornerRadius: 16, style: .continuous)
                .strokeBorder(colors.outlineVariant, lineWidth: 1)
        )
    }
}

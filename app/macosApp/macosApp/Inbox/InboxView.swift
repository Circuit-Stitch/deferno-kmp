import Deferno
import SwiftUI

/// The Inbox Destination (ADR-0015 Inbox amendment) — the triage queue for brain-dump drafts, restyled to
/// the "See the trees" direction. The macOS twin of the iOS `InboxView`; mirrors the Compose
/// `InboxScreen`/`InboxUi`. A thin renderer of `InboxComponent`: it observes the Ready drafts and, for each,
/// offers **Accept** (commit it as a task, online-only per ADR-0016) and a calm **Dismiss** (recoverable —
/// nothing is deleted). While a create is in flight the row yields its actions to a progress strip; any
/// offline/error `note` shows gently with a clear affordance; and a dismiss surfaces an **Undo** banner. An
/// empty inbox is normal, not broken (the calm "Inbox zero" empty state).
///
/// Landed by #368 (Tranche 5c / G1). It stayed a "Coming soon" placeholder through Tranche 4 on purpose:
/// the feed is local-only (`BrainDumpDraftRepository` has no remote source), so until the Mac could capture
/// a brain dump the queue was empty by construction. Tranche 5 gave it a recorder, so it can now fill.
///
/// No pane title here: the window title bar already names "Inbox" (`MainShellView.barTitle` →
/// `.navigationTitle`) — see `header(count:)` for why the iOS twin's in-pane title is deliberately dropped.
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
            // Deliberately OUTSIDE the `if`: dismissing the *last* draft empties the list, and the Undo it
            // just offered must survive that — otherwise the one dismiss you are most likely to regret is
            // the only one you cannot take back.
            if let dismissed = value.recentlyDismissed {
                undoBanner(dismissed)
            }
        }
        .background(colors.background)
    }

    // MARK: - List

    /// A `ScrollView` + plain `VStack` of cards, not a `List` — these are cards with their own chrome and
    /// two buttons each, so `ActivityView`'s `.listStyle(.plain)` row posture doesn't transfer. `VStack`
    /// rather than `LazyVStack` matches iOS on purpose: an inbox is a short triage queue you are meant to
    /// empty, not a feed, so laziness would buy nothing and cost the scroll-height stability.
    ///
    /// The 20pt gutter is the iOS card gutter, kept — `PlanView` (the other macOS card list) uses the same
    /// 20 rather than the tighter `Layout.gutter`. That token is a *row* density decision (`ActivityView`'s
    /// list insets); a card that hugs the window edge just reads as a broken margin.
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

    /// The header band — the draft count and the "review each one" reassurance. Deliberately *without* the
    /// iOS twin's 22pt "Inbox" title: the macOS shell already names the foreground surface in the window
    /// title bar (`MainShellView.barTitle` → `.navigationTitle`), so repeating it here would title the pane
    /// twice. `ActivityView`'s count band made the same call for the same reason, and it is the rule
    /// `ProfileView`/`SettingsView` state as "No PaneHeader: the single adaptive shell bar titles it".
    ///
    /// Dropping the title costs no heading navigation: the `SectionLabel` below already carries
    /// `.isHeader` (Atoms.swift), so VoiceOver's heading rotor still lands on "Waiting for you". The
    /// *subtitle* stays — it is instructional prose ("nothing's deleted"), not a repeat of the pane's name.
    ///
    /// The `.frame(maxWidth: .infinity, alignment: .leading)` is the *consequence* of that drop, not a
    /// second decision: the iOS twin got its full-width header for free from the title row's
    /// `Spacer(minLength: 12)`, which went with the title. Without it the band would shrink-wrap the
    /// mono count and the subtitle would no longer share a left edge with the cards below.
    private func header(count: Int) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            MonoMeta(draftCount(count))
            Text(L.string("inbox_header_subtitle"))
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
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

    /// The recoverable-dismiss banner: what was dismissed, and the one link that puts it back. Combined into
    /// a single accessibility element so VoiceOver reads "Dismissed 'X', Undo" as one stop rather than two.
    ///
    /// `Layout.minTouchTarget` resolves to 28 here against the iOS twin's 48 — that divergence is the token
    /// doing its job (a pointer hits the shorter band fine), not a layout decision taken at this site.
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

    /// "1 draft" / "N drafts" — the quiet count above the section label.
    private func draftCount(_ n: Int) -> String { L.plural("inbox_draft_count", n) }
}

private extension InboxRow {
    /// Stable String identity for SwiftUI list diffing — keyed off the draft's bridge key (an
    /// `InboxRow` is a Kotlin data class, so this is its `\.self` substitute).
    var bridgeKey: String { ShellBridgeKt.inboxDraftKey(draft: draft) }
}

/// One reviewable draft as a "See the trees" card: a `DRAFTED` eyebrow with a quiet **Dismiss** in the
/// corner, the title, an optional "Due …" mono line, the dictated notes, any gentle offline/error note
/// (with a Clear affordance), and a **Accept** primary action. While the create is in flight (`accepting`)
/// the action yields to a progress strip and Dismiss hides (clicks are ignored).
///
/// No hover cue and no `.help()`, unlike most macOS ports of an iOS card: there is no flat click surface
/// here — every affordance is already an explicit button carrying a visible text label, and `.help` lands
/// as a VoiceOver *hint*, so it would only say the label a second time (the rule `MainShellView`'s sidebar
/// rows state).
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
            // macOS is the platform where copy-out matters, and these three Texts are the only prose on the
            // card: the extracted title and the dictated notes (which a person may well want to paste
            // elsewhere before accepting) and the server's own error text (which is what you quote in a bug
            // report). The established idiom here — `TaskDetailView` and `ChangeDiffSheet` already do
            // exactly this. Nothing is stolen from a click: the card has no tap gesture, only its buttons.
            Text(draft.title)
                .font(.headline)
                .foregroundStyle(colors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
                .textSelection(.enabled)
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
                    .textSelection(.enabled)
            }
            if let note, !note.isEmpty {
                Spacer().frame(height: 8)
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(note)
                        .font(.footnote)
                        .foregroundStyle(colors.error)
                        .fixedSize(horizontal: false, vertical: true)
                        .textSelection(.enabled)
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

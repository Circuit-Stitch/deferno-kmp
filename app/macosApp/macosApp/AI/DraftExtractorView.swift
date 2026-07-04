import Deferno
import SwiftUI

/// Phase-3 (ADR-0029) dev panel: run the **propose-only** Brain-dump Extractor over a transcript through
/// the on-device Foundation Models engine and list the draft Tasks it proposes — nothing is committed.
/// Self-contained in the macOS app (it doesn't touch the shared shell); reached from the
/// "Apple Intelligence" menu. The real product surface (dictate → extract → accept into the New overlay)
/// is a follow-up; this proves the seam end-to-end on-device.
struct DraftExtractorView: View {
    let bridge: DraftTasksBridge?

    @State private var transcript = DraftExtractorView.sample
    @State private var drafts: [DraftPreview] = []
    @State private var status: String?
    @State private var running = false
    @Environment(\.dismiss) private var dismiss

    private static let sample = """
    Pick up the dry cleaning tomorrow afternoon. Email Dana the budget before Friday — it's blocking \
    the vendor sign-off. And sometime this week, renew the car registration.
    """

    private var available: Bool { bridge?.isAvailable == true }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.string("draft_extract_title")).font(.title2.bold())
            Text(L.string("draft_extract_intro"))
                .font(.caption).foregroundStyle(.secondary)

            TextEditor(text: $transcript)
                .font(.body)
                .frame(minHeight: 96)
                .overlay(RoundedRectangle(cornerRadius: 6).stroke(.quaternary))

            HStack {
                Button(running ? L.string("draft_extract_running") : L.string("draft_extract_button"), action: run)
                    .keyboardShortcut(.return, modifiers: .command)
                    .disabled(running || !available || transcript.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                if running { ProgressView().controlSize(.small) }
                Spacer()
                Button(L.string("calendar_action_done")) { dismiss() }
            }

            if !available {
                Label(L.string("draft_extract_unavailable"), systemImage: "exclamationmark.triangle")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let status {
                Text(status).font(.caption).foregroundStyle(.secondary)
            }

            if !drafts.isEmpty {
                Divider()
                ScrollView {
                    VStack(alignment: .leading, spacing: 10) {
                        ForEach(Array(drafts.enumerated()), id: \.offset) { _, draft in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(draft.title).font(.body.weight(.medium))
                                if let subtitle = draft.subtitle {
                                    Text(subtitle).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                        }
                    }
                }
            }
            Spacer(minLength: 0)
        }
        .padding(20)
        .frame(width: 460, height: 480)
    }

    private func run() {
        guard let bridge else { return }
        running = true
        status = nil
        drafts = []
        bridge.extract(
            transcript: transcript,
            onResult: { result in
                drafts = result
                status = result.isEmpty
                    ? L.string("draft_extract_none")
                    : L.plural("draft_extract_proposed", result.count)
                running = false
            },
            onFailure: { reason, detail in
                // `detail` is a content-free diagnostic — kept for logs, never shown (#327).
                _ = detail
                status = L.draftExtractError(reason)
                running = false
            }
        )
    }
}

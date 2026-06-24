import Deferno
import SwiftUI

/// The New create overlay (#71) — a thin renderer of `NewComponent`. An **explicit** Task/Habit/Chore/
/// Event kind picker (never inferred, ADR-0015) over a per-kind form, dispatched through the shell's
/// **online-only** create seam (ADR-0016): on success the shell dismisses; offline shows a gentle
/// "reconnect to save"; a server error shows a gentle message — never a silent failure. Dictation (#92,
/// ADR-0029 Phase 2) fills the title/notes on-device via `MacDictation`: the mic shows when the engine is
/// available, fills text only (never the kind, ADR-0015), and surfaces permission/engine problems gently.
struct NewItemView: View {
    let component: NewComponent
    @StateObject private var state: StateFlowObserver<NewState>
    @Environment(\.defernoColors) private var colors
    @State private var dateText = ""
    @State private var startText = ""
    @State private var endText = ""
    @State private var seeded = false

    init(component: NewComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            HStack {
                Text("New").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Cancel") { component.dismiss() }
            }
            .padding(.horizontal, Layout.gutter)
            .frame(minHeight: 56)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    kindPicker(value)
                    titleField(value)
                    notesField(value)
                    if isEvent(value) { eventFields } else { dateField }
                    dictationMessage(value)
                    statusMessage(value)
                    createButton(value)
                }
                .padding(Layout.gutter)
            }
        }
        .background(colors.background)
        .onAppear {
            guard !seeded else { return }
            seeded = true
            dateText = ShellBridgeKt.formatLocalDate(date: value.date)
            startText = ShellBridgeKt.formatInstant(instant: value.start)
            endText = ShellBridgeKt.formatInstant(instant: value.end)
        }
    }

    private func kindPicker(_ value: NewState) -> some View {
        HStack(spacing: 8) {
            ForEach(ShellBridgeKt.itemKinds().indices, id: \.self) { i in
                let kind = ShellBridgeKt.itemKinds()[i]
                SelectableChip(
                    label: kind.name,
                    selected: ShellBridgeKt.itemKindsEqual(a: value.selectedKind, b: kind)
                ) { component.selectKind(kind: kind) }
            }
        }
        .accessibilityLabel("Kind picker")
    }

    private func titleField(_ value: NewState) -> some View {
        HStack(spacing: 8) {
            TextField("Title", text: Binding(get: { state.value.title }, set: { component.setTitle(title: $0) }))
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("Title")
            micButton(.title, value)
        }
    }

    private func notesField(_ value: NewState) -> some View {
        HStack(alignment: .top, spacing: 8) {
            TextField("Notes", text: Binding(get: { state.value.notes }, set: { component.setNotes(notes: $0) }), axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel("Notes")
            micButton(.notes, value)
        }
    }

    /// The per-field dictation mic (#92, ADR-0029 Phase 2), shown only when the on-device engine is
    /// available. Tapping toggles capture on that field; a tap while it's listening stops, keeping the
    /// text streamed so far (it's ordinary editable text from there, ADR-0018).
    @ViewBuilder
    private func micButton(_ field: DictationField, _ value: NewState) -> some View {
        if value.dictationAvailable {
            let listening = ShellBridgeKt.dictationListeningField(state: value, field: field)
            Button {
                if listening { component.stopDictation() } else { component.startDictation(field: field) }
            } label: {
                Image(systemName: listening ? "mic.fill" : "mic")
                    .foregroundStyle(listening ? colors.primary : colors.inkMuted)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(listening ? "Stop dictation" : "Dictate")
        }
    }

    /// A gentle, honest line for a settled dictation problem (permission/engine) — never a silent failure.
    @ViewBuilder
    private func dictationMessage(_ value: NewState) -> some View {
        if let message = ShellBridgeKt.dictationMessage(state: value) {
            Text(message).font(.footnote).foregroundStyle(colors.inkMuted)
                .accessibilityLabel(message)
        }
    }

    private var dateField: some View {
        TextField("Date (optional, e.g. 2026-06-08)", text: $dateText)
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled(true)
            .onChange(of: dateText) { _ in component.setDate(date: ShellBridgeKt.parseLocalDate(text: dateText)) }
            .accessibilityLabel("Date")
    }

    private var eventFields: some View {
        VStack(spacing: 8) {
            TextField("Starts (e.g. 2026-06-08T09:00:00Z)", text: $startText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled(true)
                .onChange(of: startText) { _ in component.setStart(start: ShellBridgeKt.parseInstant(text: startText)) }
                .accessibilityLabel("Event start")
            TextField("Ends (optional)", text: $endText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled(true)
                .onChange(of: endText) { _ in component.setEnd(end: ShellBridgeKt.parseInstant(text: endText)) }
                .accessibilityLabel("Event end")
        }
    }

    @ViewBuilder
    private func statusMessage(_ value: NewState) -> some View {
        if ShellBridgeKt.doNewStatusIsOffline(state: value) {
            Text("You're offline — reconnect to save. Nothing was queued.")
                .font(.footnote).foregroundStyle(colors.inkMuted)
                .accessibilityLabel("Reconnect to save")
        } else if let message = ShellBridgeKt.doNewStatusFailedMessage(state: value) {
            Text(message).font(.footnote).foregroundStyle(colors.error)
        }
    }

    private func createButton(_ value: NewState) -> some View {
        Button { component.submit() } label: {
            Text(ShellBridgeKt.doNewStatusIsSubmitting(state: value) ? "Saving…" : "Create")
                .frame(maxWidth: .infinity).frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!value.canSubmit)
    }

    private func isEvent(_ value: NewState) -> Bool {
        ShellBridgeKt.itemKindsEqual(a: value.selectedKind, b: ItemKind.event)
    }
}

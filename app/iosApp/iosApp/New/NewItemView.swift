import Deferno
import SwiftUI

/// The New create overlay (#71) — a thin renderer of `NewComponent`. An **explicit** Task/Habit/Chore/
/// Event kind picker (never inferred, ADR-0015) over a per-kind form, dispatched through the shell's
/// **online-only** create seam (ADR-0016): on success the shell dismisses; offline shows a gentle
/// "reconnect to save"; a server error shows a gentle message — never a silent failure. Dictation (#92/#268,
/// ADR-0018) fills the title/notes on-device via `IosDictation` (SFSpeech): the mic shows when the engine is
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
                Text(L.string("shell_new")).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(L.string("common_cancel")) { component.dismiss() }
            }
            .padding(.horizontal, Layout.gutter)
            .frame(minHeight: 56)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    kindPicker(value)
                    titleField(value)
                    notesField(value)
                    if isEvent(value) {
                        eventFields
                    } else {
                        dateField
                        deadlineTimeField(value)
                    }
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
                let selected = ShellBridgeKt.itemKindsEqual(a: value.selectedKind, b: kind)
                Button(L.kindLabel(kind.name)) { component.selectKind(kind: kind) }
                    .font(.subheadline)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(selected ? colors.primary : colors.surfaceVariant, in: Capsule())
                    .foregroundStyle(selected ? colors.onPrimary : colors.onSurface)
            }
        }
        .accessibilityLabel(L.string("new_kind_picker_cd"))
    }

    private func titleField(_ value: NewState) -> some View {
        HStack(spacing: 8) {
            TextField(L.string("new_title_label"), text: Binding(get: { state.value.title }, set: { component.setTitle(title: $0) }))
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(L.string("new_title_label"))
            micButton(.title, value)
        }
    }

    private func notesField(_ value: NewState) -> some View {
        HStack(alignment: .top, spacing: 8) {
            TextField(L.string("new_notes_label"), text: Binding(get: { state.value.notes }, set: { component.setNotes(notes: $0) }), axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.roundedBorder)
                .accessibilityLabel(L.string("new_notes_label"))
            micButton(.notes, value)
        }
    }

    /// The per-field dictation mic (#92/#268, ADR-0018), shown only when the on-device engine is available.
    /// Tapping toggles capture on that field; a tap while listening stops, keeping the streamed text (it's
    /// ordinary editable text from there, ADR-0018).
    @ViewBuilder
    private func micButton(_ field: DictationField, _ value: NewState) -> some View {
        if value.dictationAvailable {
            let listening = ShellBridgeKt.doNewDictationListeningField(state: value, field: field)
            Button {
                if listening { component.stopDictation() } else { component.startDictation(field: field) }
            } label: {
                Image(systemName: listening ? "mic.fill" : "mic")
                    .foregroundStyle(listening ? colors.primary : colors.inkMuted)
            }
            .buttonStyle(.borderless)
            .accessibilityLabel(listening ? L.string("new_mic_stop_dictation_cd") : L.string("new_mic_dictate_cd"))
        }
    }

    /// A gentle, honest line for a settled dictation problem (permission/engine) — never a silent failure.
    @ViewBuilder
    private func dictationMessage(_ value: NewState) -> some View {
        if let message = ShellBridgeKt.doNewDictationMessage(state: value) {
            Text(message).font(.footnote).foregroundStyle(colors.inkMuted)
                .accessibilityLabel(message)
        }
    }

    private var dateField: some View {
        TextField(L.string("new_date_label"), text: $dateText)
            .textFieldStyle(.roundedBorder)
            .autocorrectionDisabled(true)
            .onChange(of: dateText) { _ in component.setDate(date: ShellBridgeKt.parseLocalDate(text: dateText)) }
            .accessibilityLabel(L.string("new_date_cd"))
    }

    /// The deadline time-of-day row (#348) — shown for the non-Event kinds alongside the date, mirroring
    /// Android's `NewDeadlineTimeField`. A `.hourAndMinute` DatePicker bound to the shared deadline-time
    /// seam: `setNewDeadlineTime(component:hour:minute:)` on change, `clearNewDeadlineTime(component:)` to
    /// remove it. The bridge returns -1 for "no time set"; the row offers an explicit "Add" affordance so
    /// the field stays unset until the person chooses a time, and a "Clear" button to unset it again.
    @ViewBuilder
    private func deadlineTimeField(_ value: NewState) -> some View {
        let hour = Int(ShellBridgeKt.doNewDeadlineTimeHour(state: value))
        let minute = Int(ShellBridgeKt.doNewDeadlineTimeMinute(state: value))
        let hasTime = hour >= 0 && minute >= 0
        HStack(spacing: 8) {
            Text(L.string("tasks_detail_property_time"))
                .font(.subheadline)
                .foregroundStyle(colors.onSurfaceVariant)
            Spacer()
            if hasTime {
                DatePicker(
                    "",
                    selection: deadlineTimeBinding(hour: hour, minute: minute),
                    displayedComponents: .hourAndMinute
                )
                .labelsHidden()
                .accessibilityLabel(L.string("new_deadline_time_cd"))
                Button(L.string("common_clear")) { ShellBridgeKt.clearNewDeadlineTime(component: component) }
                    .font(.footnote)
                    .accessibilityLabel(L.string("new_deadline_time_clear_a11y"))
            } else {
                Button(L.string("common_add")) { ShellBridgeKt.setNewDeadlineTime(component: component, hour: 9, minute: 0) }
                    .font(.subheadline)
                    .accessibilityLabel(L.string("new_deadline_time_add_a11y"))
            }
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// A `Date` binding over the shared deadline-time hour/minute. The wall-clock components are the only
    /// meaningful payload (the day is irrelevant); reading composes today's date with the stored time,
    /// writing extracts the picked hour/minute back through `setNewDeadlineTime`.
    private func deadlineTimeBinding(hour: Int, minute: Int) -> Binding<Date> {
        Binding(
            get: {
                Calendar.current.date(
                    bySettingHour: max(0, hour),
                    minute: max(0, minute),
                    second: 0,
                    of: Date()
                ) ?? Date()
            },
            set: { picked in
                let parts = Calendar.current.dateComponents([.hour, .minute], from: picked)
                ShellBridgeKt.setNewDeadlineTime(
                    component: component,
                    hour: Int32(parts.hour ?? 0),
                    minute: Int32(parts.minute ?? 0)
                )
            }
        )
    }

    private var eventFields: some View {
        VStack(spacing: 8) {
            TextField(L.string("new_event_start_label"), text: $startText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled(true)
                .onChange(of: startText) { _ in component.setStart(start: ShellBridgeKt.parseInstant(text: startText)) }
                .accessibilityLabel(L.string("new_event_start_cd"))
            TextField(L.string("new_event_end_label"), text: $endText)
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled(true)
                .onChange(of: endText) { _ in component.setEnd(end: ShellBridgeKt.parseInstant(text: endText)) }
                .accessibilityLabel(L.string("new_event_end_cd"))
        }
    }

    @ViewBuilder
    private func statusMessage(_ value: NewState) -> some View {
        if ShellBridgeKt.doNewStatusIsOffline(state: value) {
            Text(L.string("new_offline_note"))
                .font(.footnote).foregroundStyle(colors.inkMuted)
                .accessibilityLabel(L.string("common_reconnect_to_save"))
        } else if let message = L.newFailure(value) {
            Text(message).font(.footnote).foregroundStyle(colors.error)
        }
    }

    private func createButton(_ value: NewState) -> some View {
        Button { component.submit() } label: {
            Text(ShellBridgeKt.doNewStatusIsSubmitting(state: value) ? L.string("new_submit_saving") : L.string("new_submit_create"))
                .frame(maxWidth: .infinity).frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!value.canSubmit)
    }

    private func isEvent(_ value: NewState) -> Bool {
        ShellBridgeKt.itemKindsEqual(a: value.selectedKind, b: ItemKind.event)
    }
}

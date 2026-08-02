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
                        eventFields(value)
                    } else {
                        dateField(value)
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
    }

    private func kindPicker(_ value: NewState) -> some View {
        HStack(spacing: 8) {
            ForEach(ShellBridgeKt.itemKinds().indices, id: \.self) { i in
                let kind = ShellBridgeKt.itemKinds()[i]
                SelectableChip(
                    label: L.kindLabel(kind.name),
                    selected: ShellBridgeKt.itemKindsEqual(a: value.selectedKind, b: kind)
                ) { component.selectKind(kind: kind) }
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
            .accessibilityLabel(listening ? L.string("new_mic_stop_dictation_cd") : L.string("new_mic_dictate_cd"))
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

    /// The item Date row (#74) — the Task/Habit/Chore `complete_by` anchor the Calendar FAB pre-dates. A real
    /// `DatePicker` behind `OptionalDatePickerRow`, not the ISO text field this replaced: that field parsed on
    /// every keystroke and *silently cleared the date* on anything unparseable, so a half-typed "2026-06-" quietly
    /// meant "no date". The date is genuinely optional, so the row stays on "—" + Add until the person picks one.
    private func dateField(_ value: NewState) -> some View {
        OptionalDatePickerRow(
            label: L.string("common_date"),
            accessibilityLabel: L.string("new_date_cd"),
            epochSeconds: ShellBridgeKt.doNewDateEpochSeconds(state: value),
            onPick: { ShellBridgeKt.setNewDate(component: component, epochSeconds: $0) },
            onAdd: { ShellBridgeKt.setNewDate(component: component, epochSeconds: Date().timeIntervalSince1970) },
            onClear: { component.setDate(date: nil) }
        )
    }

    /// The deadline time-of-day row (#348/#368 G12) — shown for the non-Event kinds alongside the date
    /// (an Event's clock lives in its start/end instants instead). Until this landed, macOS had **no**
    /// time control at all, so every item created on a Mac posted `deadlineTimeOfDay = null` however the
    /// person had set the item up elsewhere — a data-fidelity gap, not a cosmetic one.
    ///
    /// The bridge encodes "no time set" as -1 (avoids a boxed `KotlinInt?`), so the row starts as an
    /// explicit "Add" affordance rather than silently defaulting to a time nobody chose, and offers
    /// "Clear" to unset it again.
    ///
    /// **Gated on a date.** `NewState.toPayload` drops `deadline_time_of_day` whenever there is no date ("a
    /// time with no day is meaningless"), so an enabled Add here on an undated form would let someone pick a
    /// time that silently never ships. Disabled + a one-line reason beats a control that lies.
    @ViewBuilder
    private func deadlineTimeField(_ value: NewState) -> some View {
        let hour = Int(ShellBridgeKt.doNewDeadlineTimeHour(state: value))
        let minute = Int(ShellBridgeKt.doNewDeadlineTimeMinute(state: value))
        let hasTime = hour >= 0 && minute >= 0
        let hasDate = ShellBridgeKt.doNewDateEpochSeconds(state: value) >= 0
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(L.string("tasks_detail_property_time"))
                    .font(.subheadline)
                    .foregroundStyle(colors.onSurfaceVariant)
                Spacer()
                if hasTime {
                    // No `.datePickerStyle`: macOS's default for a non-graphical picker is `.stepperField`,
                    // the desktop idiom (an editable HH:MM field + stepper). `.graphical` would draw an
                    // analog clock face for `.hourAndMinute`. `.labelsHidden()` is required — the empty ""
                    // title still reserves leading label width on macOS and mis-aligns the row.
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
                    Text("—").foregroundStyle(colors.inkMuted)
                    // The 9:00 seed lives in the bridge (shared with the Task-detail picker), not here.
                    Button(L.string("common_add")) { ShellBridgeKt.addNewDeadlineTime(component: component) }
                        .font(.subheadline)
                        .disabled(!hasDate)
                        .accessibilityLabel(L.string("new_deadline_time_add_a11y"))
                }
            }
            .frame(minHeight: Layout.minTouchTarget)
            if !hasDate && !hasTime {
                Text(L.string("new_time_needs_date"))
                    .font(.footnote)
                    .foregroundStyle(colors.inkMuted)
            }
        }
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

    /// The Event's two WHEN axes (#348, ADR-0051) — an **All-day** switch over a required start day and an
    /// optional end day, all real pickers. This replaced two `TextField`s that asked the person to hand-type
    /// an RFC-3339 instant ("2026-06-08T09:00:00Z"), silently clearing the field on anything unparseable —
    /// and which offered no way at all to create the all-day Event the model has always supported.
    ///
    /// **All-day is the absence of a clock, not a flag.** `all_day` is derived read-only server-side (true iff
    /// neither `*_time_of_day` is set) and rejected as input, so `value.eventIsAllDay` reads exactly the
    /// absence the payload sends — there is no second reading that could drift from it, and no "pin" needed
    /// to defend one. The switch simply adds or removes the two clocks; moving a day never touches them.
    ///
    /// The **end is genuinely optional** — an open-ended Event is valid on the wire — so it stays on "—" + Add
    /// until asked for, and Clear returns it there. The **start day is required** (`canSubmit` gates on it).
    @ViewBuilder
    private func eventFields(_ value: NewState) -> some View {
        let allDay = value.eventIsAllDay
        let axes: DatePickerComponents = allDay ? [.date] : [.date, .hourAndMinute]
        VStack(alignment: .leading, spacing: 8) {
            Toggle(L.string("common_all_day"), isOn: Binding(
                get: { allDay },
                set: { ShellBridgeKt.setNewAllDay(component: component, allDay: $0) }
            ))
            .font(.subheadline)
            .accessibilityLabel(L.string("common_all_day"))

            OptionalDatePickerRow(
                label: L.string("common_starts"),
                accessibilityLabel: L.string("new_event_start_cd"),
                epochSeconds: ShellBridgeKt.doNewEventStartEpochSeconds(state: value),
                components: axes,
                onPick: { ShellBridgeKt.setNewEventStart(component: component, epochSeconds: $0) },
                // Seeds today, all-day — a day needs no clock, and turning All-day off is how you get one.
                onAdd: { ShellBridgeKt.addNewEventStart(component: component) }
            )

            OptionalDatePickerRow(
                label: L.string("common_ends"),
                accessibilityLabel: L.string("new_event_end_cd"),
                epochSeconds: ShellBridgeKt.doNewEventEndEpochSeconds(state: value),
                components: axes,
                onPick: { ShellBridgeKt.setNewEventEnd(component: component, epochSeconds: $0) },
                // Withheld until there is a start day to hang it off — an end with no start is not a window,
                // and the seam would no-op. `nil` renders the row as a plain "—" with no live button.
                onAdd: value.date != nil ? { ShellBridgeKt.addNewEventEnd(component: component) } : nil,
                onClear: { component.setEndDate(date: nil) }
            )

            // The one window the server rejects outright (`end_time` must be >= `complete_by`). Said here,
            // gently, rather than enqueued: create is offline-first, so an invalid Event would not come back
            // as a 400 — it would sit in the outbox and fail silently long after this screen closed.
            if value.eventEndBeforeStart {
                Text(L.string("new_event_end_before_start"))
                    .font(.footnote)
                    .foregroundStyle(colors.error)
            }
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

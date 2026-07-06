import Deferno
import SwiftUI

/// The Task detail pane (#195). Thin renderer of `TaskDetailComponent`: observes the hydrating row and
/// forwards the close / add-to-plan / working-state intents plus the web-parity detail sections —
/// editable PROPERTIES (Due / Labels) and the nested SUBTASKS outline (checkbox + progress + drill).
/// The component hydrates on creation (summary → full, #22); this View just reflects its state. The
/// detail component is shared with the inline pane and the (future) detached window (#196), so this
/// upgrades both surfaces at once. The subtasks section also hosts Add-subtask + a "Hide done" filter
/// (#197b/c); Attachments + the ACTIVITY/comments feed (#197a) are still deferred (not built here).
struct TaskDetailView: View {
    let component: TaskDetailComponent
    /// Hide the header's Back control. Set at a detached window's root entry (#196, depth 1) — it has
    /// nothing to pop and the window's own chrome closes it. Default false: the inline / Plan callers
    /// keep their Back (which routes through the detail's `Closed` output to close the pane / pop).
    var hidesBackControl: Bool = false
    /// Drop the in-pane `PaneHeader` entirely. Set by the Plan tier-3 host (#51), where the single adaptive
    /// shell bar already shows the Task title + ← back; default true keeps the header for the inline Tasks
    /// pane and the detached detail window.
    var showsHeader: Bool = true
    @StateObject private var state: StateFlowObserver<TaskDetailState>
    @State private var newLabel = ""
    @State private var newSubtask = ""

    init(component: TaskDetailComponent, hidesBackControl: Bool = false, showsHeader: Bool = true) {
        self.component = component
        self.hidesBackControl = hidesBackControl
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            // In single-pane the leading control returns to the list, so it reads as "Back". Hidden at a
            // detached window's root entry (#196), where there's nothing to pop to. Dropped entirely in the
            // Plan tier-3 host (#51), where the shell bar shows the title + back.
            if showsHeader {
                PaneHeader(
                    title: value.task?.title ?? L.string("common_kind_task"),
                    onBack: hidesBackControl ? nil : { component.onCloseClicked() }
                )
            }
            if value.isHydrating && value.task == nil {
                LoadingStrip(label: L.string("tasks_detail_loading"))
            }
            if value.task == nil && !value.isHydrating {
                EmptyStateView(
                    title: L.string("tasks_detail_not_found_title"),
                    message: L.string("tasks_detail_not_found_body")
                )
            } else if let task = value.task {
                taskBody(for: task, state: value)
            } else {
                Spacer() // brief hydrating gap before the row is observed; the strip above shows it
            }
        }
        .background(Color(nsColor: .windowBackgroundColor))
    }

    @ViewBuilder
    private func taskBody(for task: Task, state value: TaskDetailState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                if let ref = task.ref {
                    Text(ref)
                        .font(.footnote.monospaced())
                        .foregroundStyle(.secondary)
                }

                WorkingStateEditorView(current: task.workingState) { component.onSetWorkingState(target: $0) }

                if let description = task.description_, !description.isEmpty {
                    Text(description).font(.body)
                } else if !value.isHydrating {
                    Text(L.string("tasks_detail_no_description"))
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Button { component.onAddToPlanClicked() } label: {
                    Text(L.string("tasks_menu_add_to_plan")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .controlSize(.large)
                .frame(minHeight: Layout.minTouchTarget)

                Divider()
                propertiesSection(task)
                Divider()
                subtasksSection(value)
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
    }

    // MARK: - Properties (Due · Time · Labels · Owner)

    @ViewBuilder
    private func propertiesSection(_ task: Task) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(L.string("tasks_detail_section_properties"))
            dueRow(task)
            propertyRow(label: L.string("tasks_detail_property_time"), value: BridgeKt.taskTimeLabel(task: task))
            labelsRow(task)
            propertyRow(label: L.string("tasks_detail_property_owner"), value: BridgeKt.taskOwnerLabel(task: task))
        }
    }

    // A fixed-width label + flexible value keeps each row single-column, so it survives the 250pt inline
    // pane minimum (#194) without per-character wrapping — no explicit ≤320pt reflow needed.
    private func propertyRow(label: String, value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
            Text(value).font(.body).foregroundStyle(value == "—" ? .secondary : .primary)
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The editable DUE row: a native `DatePicker` when a deadline is set (with a Clear button), else a
    /// "Set" button that seeds today. Confirming forwards the picked day; Clear forwards nil. TIME stays
    /// read-only — there is no time-of-day write seam on the component yet (its own follow-up).
    @ViewBuilder
    private func dueRow(_ task: Task) -> some View {
        let hasDue = BridgeKt.taskDeadlineEpochSeconds(task: task) >= 0
        HStack {
            if hasDue {
                DatePicker(
                    L.string("tasks_detail_property_due"),
                    selection: Binding(
                        get: { Date(timeIntervalSince1970: BridgeKt.taskDeadlineEpochSeconds(task: task)) },
                        set: { BridgeKt.setTaskDeadline(component: component, epochSeconds: $0.timeIntervalSince1970) }
                    ),
                    displayedComponents: .date
                )
                .datePickerStyle(.field)
                Button(L.string("common_clear")) { BridgeKt.clearTaskDeadline(component: component) }
                    .font(.subheadline)
                    .accessibilityLabel(L.string("tasks_detail_clear_due_date_a11y"))
            } else {
                Text(L.string("tasks_detail_property_due")).font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
                Text("—").foregroundStyle(.secondary)
                Spacer()
                Button(L.string("common_set")) { BridgeKt.setTaskDeadline(component: component, epochSeconds: Date().timeIntervalSince1970) }
                    .font(.subheadline)
                    .accessibilityLabel(L.string("tasks_detail_set_due_date"))
            }
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    @ViewBuilder
    private func labelsRow(_ task: Task) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L.string("common_labels")).font(.subheadline).foregroundStyle(.secondary)
            if !task.labels.isEmpty {
                ScrollView(.horizontal, showsIndicators: false) {
                    HStack(spacing: 8) {
                        ForEach(task.labels, id: \.self) { label in
                            HStack(spacing: 4) {
                                Text(label).font(.subheadline)
                                Button {
                                    component.onSetLabels(labels: normalize(task.labels.filter { $0 != label }))
                                } label: {
                                    Image(systemName: "xmark.circle.fill").font(.caption)
                                }
                                .buttonStyle(.plain)
                                .accessibilityLabel(L.format("tasks_detail_remove_label_a11y", label))
                            }
                            .padding(.horizontal, 10).padding(.vertical, 6)
                            .background(Color(.secondarySystemFill), in: Capsule())
                        }
                    }
                }
            }
            addLabelField
        }
    }

    private var addLabelField: some View {
        HStack {
            TextField(L.string("tasks_detail_add_label_placeholder"), text: $newLabel)
                .textFieldStyle(.roundedBorder)
                .onSubmit { submitLabel() }
            Button(L.string("common_add")) { submitLabel() }
                .disabled(newLabel.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func submitLabel() {
        let entry = newLabel.trimmingCharacters(in: .whitespaces)
        guard !entry.isEmpty, let task = state.value.task else { return }
        component.onSetLabels(labels: normalize(task.labels + [entry]))
        newLabel = ""
    }

    /// Trim, drop blanks, de-dup — the same normalization the Android/iOS labels rows apply before forwarding.
    private func normalize(_ list: [String]) -> [String] {
        var seen = Set<String>()
        return list.map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    // MARK: - Subtasks (nested, expandable outline)

    @ViewBuilder
    private func subtasksSection(_ value: TaskDetailState) -> some View {
        // With "Hide done" on, the component drops Done rows from subtaskRows; the shown count is the
        // remainder (total − done). The progress bar below keeps counting the whole subtree.
        let shown = value.hideDoneSubtasks ? value.subtaskTotal - value.subtaskDone : value.subtaskTotal
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(L.string("tasks_detail_section_subtasks"), trailing: value.subtaskTotal > 0 ? "\(value.subtaskDone)/\(value.subtaskTotal)" : nil)
            if value.subtaskTotal > 0 {
                HStack {
                    Toggle(L.string("tasks_detail_filter_hide_done"), isOn: Binding(
                        get: { value.hideDoneSubtasks },
                        set: { component.onSetHideDoneSubtasks(hide: $0) }
                    ))
                    .toggleStyle(.checkbox)
                    .controlSize(.small)
                    Spacer()
                    if value.hideDoneSubtasks {
                        // Redundant compact badge — the toggle + progress bar carry the semantics for VoiceOver.
                        Text("\(shown)/\(value.subtaskTotal)")
                            .font(.footnote).foregroundStyle(.secondary)
                            .accessibilityHidden(true)
                    }
                }
                ProgressView(value: Double(value.subtaskDone), total: Double(value.subtaskTotal))
                    .accessibilityLabel(L.format("tasks_detail_subtask_progress_a11y", Int(value.subtaskDone), Int(value.subtaskTotal)))
            }
            ForEach(value.subtaskRows, id: \.task.stableKey) { row in
                SubtaskOutlineRow(
                    row: row,
                    onToggleExpand: { component.onToggleSubtaskExpand(id: $0, currentlyExpanded: $1) },
                    onToggleDone: { component.onToggleSubtaskDone(subtask: $0) },
                    onOpen: { BridgeKt.openSubtask(component: component, subtask: $0) }
                )
            }
            if value.subtaskTotal == 0 {
                Text(L.string("tasks_detail_no_subtasks_body")).font(.subheadline).foregroundStyle(.secondary)
            }
            addSubtaskField
        }
    }

    // The always-present "+ Add subtask" field (#197b) — mirrors the labels add field. Forwards through the
    // component's onAddSubtask create seam (offline-first: the child appears optimistically in the outline).
    private var addSubtaskField: some View {
        HStack {
            TextField(L.string("tasks_detail_add_subtask_placeholder"), text: $newSubtask)
                .textFieldStyle(.roundedBorder)
                .onSubmit { submitSubtask() }
            Button(L.string("common_add")) { submitSubtask() }
                .disabled(newSubtask.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func submitSubtask() {
        let entry = newSubtask.trimmingCharacters(in: .whitespaces)
        guard !entry.isEmpty else { return }
        component.onAddSubtask(title: entry)
        newSubtask = ""
    }
}

/// One depth-indented row of the subtask outline: a completion checkbox, a tappable title (opens the
/// child's own detail — re-keys the pane), the state badge, and a fold chevron when it has children. The
/// outline is pre-flattened by the component with the **same fold mechanism as the Tasks tree** (ADR-0034),
/// so a fold toggle here persists to the shared device-local store — it re-flattens the Tasks tree too and
/// survives restart — and this row draws a single level indented by `row.depth`.
private struct SubtaskOutlineRow: View {
    let row: SubtaskRow
    let onToggleExpand: (String, Bool) -> Void
    let onToggleDone: (Task) -> Void
    let onOpen: (Task) -> Void

    var body: some View {
        let task = row.task
        let done = task.workingState == WorkingState.done
        HStack(spacing: 6) {
            if row.hasChildren {
                Button { onToggleExpand(task.stableKey, row.isExpanded) } label: {
                    Image(systemName: row.isExpanded ? "chevron.down" : "chevron.right")
                        .font(.caption2).foregroundStyle(.secondary).frame(width: 14)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(row.isExpanded ? L.format("common_collapse_named_cd", task.title) : L.format("common_expand_named_cd", task.title))
            } else {
                Spacer().frame(width: 14)
            }
            Button { onToggleDone(task) } label: {
                Image(systemName: done ? "checkmark.square.fill" : "square").font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(done ? L.format("common_mark_not_done_cd", task.title) : L.format("common_mark_done_cd", task.title))
            Button { onOpen(task) } label: {
                Text(task.title)
                    // Blocked mutes (but doesn't strike) like the tree row — "blocked, not finished" (#290/#292).
                    .strikethrough(done)
                    .foregroundStyle((done || task.blocked) ? .secondary : .primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.format("common_open_named_cd", task.title))
            if task.blocked {
                DependencyBadge(text: L.string("common_blocked"), tone: .neutral, semanticLabel: L.string("common_blocked"))
            }
            WorkingStateBadge(state: task.workingState)
        }
        .frame(minHeight: Layout.minTouchTarget)
        .padding(.leading, CGFloat(row.depth) * 18)
    }
}

/// A calm section header: a heading title with an optional trailing count (e.g. "2/5").
private struct SectionTitle: View {
    let title: String
    var trailing: String?
    init(_ title: String, trailing: String? = nil) { self.title = title; self.trailing = trailing }
    var body: some View {
        HStack {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if let trailing { Text(trailing).font(.footnote).foregroundStyle(.secondary) }
        }
    }
}

/// The interactive working-state control (#73): a selectable chip per state with the current one
/// highlighted, so the user can move the Task across all five states. Tapping forwards the intent;
/// the component issues the offline-first Command and the badge flips optimistically. Plain labels,
/// large touch targets, and a self-describing VoiceOver label per chip (design-principles.md).
private struct WorkingStateEditorView: View {
    let current: WorkingState
    let onSet: (WorkingState) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(L.string("tasks_detail_working_state_heading"))
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(.secondary)
                .accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal, showsIndicators: false) {
                HStack(spacing: 8) {
                    ForEach(WorkingState.ordered, id: \.self) { state in
                        chip(state)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func chip(_ state: WorkingState) -> some View {
        let selected = state == current
        SelectableChip(
            label: state.label,
            selected: selected,
            accessibilityLabel: selected ? L.format("tasks_detail_working_state_current_a11y", state.label) : L.format("tasks_detail_set_working_state_a11y", state.label)
        ) { onSet(state) }
    }
}

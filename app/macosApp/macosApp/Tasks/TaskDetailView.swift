import Deferno
import SwiftUI

/// The Task detail pane (#195). Thin renderer of `TaskDetailComponent`: observes the hydrating row and
/// forwards the close / add-to-plan / working-state intents plus the web-parity detail sections —
/// editable PROPERTIES (Due / Labels) and the nested SUBTASKS outline (checkbox + progress + drill).
/// The component hydrates on creation (summary → full, #22); this View just reflects its state. The
/// detail component is shared with the inline pane and the (future) detached window (#196), so this
/// upgrades both surfaces at once. Attachments + Activity + Add-subtask are #197 (not built here).
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
                    title: value.task?.title ?? "Task",
                    onBack: hidesBackControl ? nil : { component.onCloseClicked() }
                )
            }
            if value.isHydrating && value.task == nil {
                LoadingStrip(label: "Loading details…")
            }
            if value.task == nil && !value.isHydrating {
                EmptyStateView(
                    title: "Task not found",
                    message: "This task may have been removed. Head back to your list."
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
                    Text("No description yet.")
                        .font(.callout)
                        .foregroundStyle(.secondary)
                }

                Button { component.onAddToPlanClicked() } label: {
                    Text("Add to today's plan").frame(maxWidth: .infinity)
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
            SectionTitle("Properties")
            dueRow(task)
            propertyRow(label: "Time", value: BridgeKt.taskTimeLabel(task: task))
            labelsRow(task)
            propertyRow(label: "Owner", value: BridgeKt.taskOwnerLabel(task: task))
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
                    "Due",
                    selection: Binding(
                        get: { Date(timeIntervalSince1970: BridgeKt.taskDeadlineEpochSeconds(task: task)) },
                        set: { BridgeKt.setTaskDeadline(component: component, epochSeconds: $0.timeIntervalSince1970) }
                    ),
                    displayedComponents: .date
                )
                .datePickerStyle(.field)
                Button("Clear") { BridgeKt.clearTaskDeadline(component: component) }
                    .font(.subheadline)
                    .accessibilityLabel("Clear due date")
            } else {
                Text("Due").font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
                Text("—").foregroundStyle(.secondary)
                Spacer()
                Button("Set") { BridgeKt.setTaskDeadline(component: component, epochSeconds: Date().timeIntervalSince1970) }
                    .font(.subheadline)
                    .accessibilityLabel("Set due date")
            }
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    @ViewBuilder
    private func labelsRow(_ task: Task) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("Labels").font(.subheadline).foregroundStyle(.secondary)
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
                                .accessibilityLabel("Remove label \(label)")
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
            TextField("Add a label…", text: $newLabel)
                .textFieldStyle(.roundedBorder)
                .onSubmit { submitLabel() }
            Button("Add") { submitLabel() }
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
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle("Subtasks", trailing: value.subtaskTotal > 0 ? "\(value.subtaskDone)/\(value.subtaskTotal)" : nil)
            if value.subtaskTotal > 0 {
                ProgressView(value: Double(value.subtaskDone), total: Double(value.subtaskTotal))
                    .accessibilityLabel("\(value.subtaskDone) of \(value.subtaskTotal) subtasks done")
                ForEach(value.subtaskRows, id: \.task.stableKey) { row in
                    SubtaskOutlineRow(
                        row: row,
                        onToggleExpand: { component.onToggleSubtaskExpand(id: $0, currentlyExpanded: $1) },
                        onToggleDone: { component.onToggleSubtaskDone(subtask: $0) },
                        onOpen: { BridgeKt.openSubtask(component: component, subtask: $0) }
                    )
                }
            } else {
                Text("No smaller steps yet.").font(.subheadline).foregroundStyle(.secondary)
            }
        }
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
                .accessibilityLabel(row.isExpanded ? "Collapse \(task.title)" : "Expand \(task.title)")
            } else {
                Spacer().frame(width: 14)
            }
            Button { onToggleDone(task) } label: {
                Image(systemName: done ? "checkmark.square.fill" : "square").font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(done ? "Mark \(task.title) not done" : "Mark \(task.title) done")
            Button { onOpen(task) } label: {
                Text(task.title)
                    // Blocked mutes (but doesn't strike) like the tree row — "blocked, not finished" (#290/#292).
                    .strikethrough(done)
                    .foregroundStyle((done || task.blocked) ? .secondary : .primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open \(task.title)")
            if task.blocked {
                DependencyBadge(text: "Blocked", tone: .neutral, semanticLabel: "Blocked")
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
            Text("Working state")
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
            accessibilityLabel: selected ? "\(state.label), current working state" : "Set to \(state.label)"
        ) { onSet(state) }
    }
}

import Deferno
import SwiftUI

/// The Task detail pane (#195, reshaped by ADR-0044). Thin renderer of `TaskDetailComponent`: observes the
/// hydrating row and forwards the close / add-to-plan / status intents. The single heading is now a
/// **connected-parent header** (the immediate parent, tappable → pushes its detail) and the body is three
/// tabs — **Info** (NOTES → Add-to-plan → PROPERTIES → SUBTASKS) · **Comments** · **History**. STATUS is a
/// read-only journey indicator that opens a status-picker sheet on tap (the inline working-state chips are
/// gone). The component hydrates on creation (summary → full, #22); this View just reflects its state, and
/// is shared with the inline pane and the (future) detached window (#196). Attachments stay deferred on macOS.
struct TaskDetailView: View {
    let component: TaskDetailComponent
    /// Hide the header's Back control. Set at a detached window's root entry (#196, depth 1) — it has
    /// nothing to pop and the window's own chrome closes it. Default false: the inline / Plan callers
    /// keep their Back (which routes through the detail's `Closed` output to close the pane / pop).
    var hidesBackControl: Bool = false
    /// Drop the in-pane `DrilledBackBar` entirely. Set by the Plan tier-3 host (#51), where the single
    /// adaptive shell bar already shows the ← back; default true keeps the bar for the inline Tasks pane
    /// and the detached detail window. (The connected-parent node is the heading in every case.)
    var showsHeader: Bool = true
    @StateObject private var state: StateFlowObserver<TaskDetailState>
    @State private var newLabel = ""
    @State private var newSubtask = ""
    @State private var commentDraft = ""
    /// The active body tab (ADR-0044: Info · Comments · History, Info default). The View is re-created
    /// per Task via `.id(detailKey)`, so this resets when the pane re-keys to another item.
    @State private var tab: DetailTab = .info
    /// Whether the status-picker sheet is up (opened by tapping the STATUS row — the inline chips are gone).
    @State private var showingStatusPicker = false
    /// Whether the overflow's Delete confirmation is up.
    @State private var showingDeleteConfirm = false
    @FocusState private var subtaskFieldFocused: Bool

    enum DetailTab { case info, comments, history }

    init(component: TaskDetailComponent, hidesBackControl: Bool = false, showsHeader: Bool = true) {
        self.component = component
        self.hidesBackControl = hidesBackControl
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            // ADR-0044: the connected-parent node is the single heading, so this bar is title-less — just a
            // Back affordance. In single-pane it returns to the list ("Back"). Hidden at a detached window's
            // root entry (#196, nothing to pop) and dropped in the Plan tier-3 host (#51, the shell bar backs).
            if showsHeader {
                DrilledBackBar(onBack: hidesBackControl ? nil : { component.onCloseClicked() })
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
                // The single heading (ADR-0044): the tappable immediate-parent node thread-connected above
                // the current item's title + ref, with the self-contained overflow riding top-right.
                ConnectedParentHeader(
                    parent: value.parent,
                    task: task,
                    onOpenParent: {
                        if let p = value.parent { BridgeKt.openParent(component: component, parent: p) }
                    },
                    overflow: { overflowMenu }
                )

                Picker("", selection: $tab) {
                    Text(L.string("tasks_detail_tab_info")).tag(DetailTab.info)
                    Text(L.string("tasks_detail_tab_comments")).tag(DetailTab.comments)
                    Text(L.string("tasks_detail_tab_history")).tag(DetailTab.history)
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                switch tab {
                case .info: infoTab(task, value)
                case .comments: commentsTab(value)
                case .history: historyTab(value)
                }
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
        .sheet(isPresented: $showingStatusPicker) {
            StatusPickerSheet(current: task.workingState) {
                component.onSetWorkingState(target: $0)
                showingStatusPicker = false
            }
        }
        .confirmationDialog(
            L.string("tasks_detail_delete_confirm_title"),
            isPresented: $showingDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button(L.string("common_delete"), role: .destructive) { component.onDelete() }
            Button(L.string("common_cancel"), role: .cancel) {}
        }
        // ADR-0044: the drilled-overflow "Add subtask" reveals the inline composer. macOS has no compact
        // shell bar, so the overflow lives in the header; reacting here focuses the add-subtask field.
        // TODO(port-verify): confirm the focus lands and the Info tab is showing after an Xcode build.
        .onChange(of: value.revealAddSubtaskComposer) { token in
            if token > 0 { tab = .info; subtaskFieldFocused = true }
        }
    }

    // MARK: - Tabs

    @ViewBuilder
    private func infoTab(_ task: Task, _ value: TaskDetailState) -> some View {
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

    @ViewBuilder
    private func commentsTab(_ value: TaskDetailState) -> some View {
        let comments = value.activity.filter { BridgeKt.activityItemComment(item: $0) != nil }
        VStack(alignment: .leading, spacing: 8) {
            commentComposer(isPosting: value.isPostingComment)
            if value.commentsLoading && comments.isEmpty {
                MutedLine(L.string("common_loading"))
            } else if comments.isEmpty {
                MutedLine(L.string("tasks_detail_no_comments"))
            } else {
                ForEach(comments.map(ActivityRow.init)) { row in
                    if let comment = BridgeKt.activityItemComment(item: row.item) {
                        CommentRow(
                            comment: comment,
                            isMine: BridgeKt.commentIsMine(state: value, comment: comment),
                            dateLabel: BridgeKt.commentDateLabel(comment: comment),
                            onEdit: { component.onEditComment(commentId: $0, body: $1) },
                            onDelete: { component.onDeleteComment(commentId: $0) }
                        )
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func historyTab(_ value: TaskDetailState) -> some View {
        let history = value.activity.filter { BridgeKt.activityHistoryVerb(item: $0) != nil }
        VStack(alignment: .leading, spacing: 8) {
            if value.commentsLoading && history.isEmpty {
                MutedLine(L.string("common_loading"))
            } else {
                // An empty history is a valid terminal state — no error/placeholder branch (ADR-0043).
                ForEach(history.map(ActivityRow.init)) { row in
                    if let verb = BridgeKt.activityHistoryVerb(item: row.item) {
                        HistoryRow(
                            label: L.activityHistory(verb),
                            dateLabel: BridgeKt.activityHistoryDateLabel(item: row.item) ?? ""
                        )
                    }
                }
            }
        }
    }

    // MARK: - Overflow (Add subtask · Break this down · Delete)

    /// The self-contained detail overflow (ADR-0044) — replaces the old always-visible chips' "Set aside"
    /// (Dropped now reaches only via the status picker). Add subtask bumps the reveal token; Delete confirms.
    private var overflowMenu: some View {
        Menu {
            Button { component.onAddSubtaskRequested() } label: {
                Label(L.string("tasks_menu_add_subtask"), systemImage: "plus")
            }
            Button { component.onBreakdownClicked() } label: {
                Label(L.string("tasks_menu_break_this_down"), systemImage: "wand.and.stars")
            }
            Button(role: .destructive) { showingDeleteConfirm = true } label: {
                Label(L.string("common_delete"), systemImage: "trash")
            }
        } label: {
            Image(systemName: "ellipsis.circle")
        }
        .menuStyle(.borderlessButton)
        .fixedSize()
        .accessibilityLabel(L.string("tasks_detail_more_actions"))
    }

    // MARK: - Properties (Status · When · Time · Labels · Owner)

    @ViewBuilder
    private func propertiesSection(_ task: Task) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(L.string("tasks_detail_section_properties"))
            statusRow(task)
            dueRow(task)
            propertyRow(label: L.string("tasks_detail_property_time"), value: BridgeKt.taskTimeLabel(task: task))
            labelsRow(task)
            // OWNER / SOURCE stay as trailing read-only provenance rows (spec §8, kept in the Info tab).
            propertyRow(label: L.string("tasks_detail_property_owner"), value: BridgeKt.taskOwnerLabel(task: task))
        }
    }

    /// The STATUS row (ADR-0044): a read-only journey indicator that, when tapped, opens the status picker
    /// sheet — the only path to Dropped ("NOT DOING") now. The spoken label carries the current journey label.
    @ViewBuilder
    private func statusRow(_ task: Task) -> some View {
        Button { showingStatusPicker = true } label: {
            HStack {
                Text(L.string("tasks_detail_property_status"))
                    .font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
                JourneyStatusIndicator(task: task)
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
            }
            .frame(minHeight: Layout.minTouchTarget)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(L.format("tasks_detail_status_row_a11y", L.journeyLabel(BridgeKt.journeyLabelToken(task: task))))
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
        // ADR-0044: labelled WHEN (was DUE) and, when a deadline is set, followed by the relative-day
        // reading ("In 3 days" / "Yesterday") mapped from the typed bridge token.
        HStack {
            if hasDue {
                DatePicker(
                    L.string("tasks_detail_property_when"),
                    selection: Binding(
                        get: { Date(timeIntervalSince1970: BridgeKt.taskDeadlineEpochSeconds(task: task)) },
                        set: { BridgeKt.setTaskDeadline(component: component, epochSeconds: $0.timeIntervalSince1970) }
                    ),
                    displayedComponents: .date
                )
                .datePickerStyle(.field)
                if let token = BridgeKt.taskDueRelativeToken(task: task) {
                    Text(L.relativeDay(token, Int(BridgeKt.taskDueRelativeCount(task: task))))
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Button(L.string("common_clear")) { BridgeKt.clearTaskDeadline(component: component) }
                    .font(.subheadline)
                    .accessibilityLabel(L.string("tasks_detail_clear_due_date_a11y"))
            } else {
                Text(L.string("tasks_detail_property_when")).font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
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
                .focused($subtaskFieldFocused)
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

    // MARK: - Comments composer

    /// The comment composer (unchanged write seam): a multi-line entry + Post, forwarding a trimmed,
    /// non-empty body through `onPostComment` then clearing. Disabled while a post is in flight.
    @ViewBuilder
    private func commentComposer(isPosting: Bool) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            TextField(L.string("tasks_detail_add_comment_placeholder"), text: $commentDraft, axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.roundedBorder)
                .disabled(isPosting)
                .accessibilityLabel(L.string("tasks_detail_comment_body_label"))
            Button {
                let trimmed = commentDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { component.onPostComment(body: trimmed); commentDraft = "" }
            } label: {
                Text(isPosting ? L.string("tasks_detail_posting") : L.string("tasks_detail_post"))
            }
            .buttonStyle(.borderedProminent)
            .disabled(isPosting || commentDraft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
        }
    }
}

/// The single heading (ADR-0044): the immediate parent (`Task.parentId` only) drawn as a muted, tappable
/// node — a `common_kind_task` chip + title (+ `ref`) — thread-connected above the current item's title +
/// `ref`, with the self-contained overflow riding top-right. No parent → the item stands alone. Tapping
/// the parent pushes its own detail (Back returns); the redundant `PaneHeader("Details")` is gone.
private struct ConnectedParentHeader<Overflow: View>: View {
    let parent: ParentSummary?
    let task: Task
    let onOpenParent: () -> Void
    @ViewBuilder let overflow: () -> Overflow
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if let parent {
                Button(action: onOpenParent) {
                    HStack(spacing: 6) {
                        Text(L.string("common_kind_task"))
                            .font(.caption2.weight(.medium))
                            .padding(.horizontal, 6).padding(.vertical, 2)
                            .background(colors.surfaceVariant, in: Capsule())
                            .foregroundStyle(colors.onSurfaceVariant)
                        Text(parent.title)
                            .font(.subheadline)
                            .foregroundStyle(colors.onSurfaceVariant)
                            .lineLimit(1)
                        if let ref = parent.ref {
                            Text(ref).font(.footnote.monospaced()).foregroundStyle(.secondary)
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.format("common_open_named_cd", parent.title))
                // The thin thread connector from the parent node down into the current item's title block.
                Rectangle()
                    .fill(colors.outlineVariant)
                    .frame(width: 1, height: 8)
                    .padding(.leading, 8)
            }
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(task.title)
                        .font(.title2.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    if let ref = task.ref {
                        Text(ref).font(.footnote.monospaced()).foregroundStyle(.secondary)
                    }
                }
                Spacer(minLength: 0)
                overflow()
            }
        }
    }
}

/// The title-less drilled Back bar (ADR-0044) — the connected-parent node is the heading, so this carries
/// only the Back affordance (`nil` at a detached window's root, #196). Replaces the old titled `PaneHeader`.
private struct DrilledBackBar: View {
    let onBack: (() -> Void)?

    var body: some View {
        HStack(spacing: 8) {
            if let onBack {
                Button(L.string("common_back"), action: onBack)
                    .frame(minHeight: Layout.minTouchTarget)
            }
            Spacer()
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 44)
        .background(Color(nsColor: .windowBackgroundColor))
    }
}

/// The read-only journey-status indicator (ADR-0044): a 3-slot track — initial TO-DO → a middle marker →
/// terminal DONE — read from the bridge's typed tokens (`journeyActiveSlot`/`journeyLabelToken`/shelved/
/// blocked). Colour is reinforcement only; the STATUS row's `Button` carries the accessible label, so the
/// track is `accessibilityHidden`. Shelved (NOT DOING) draws a dashed tail to a struck-through DONE; blocked
/// tints the middle slot with the error tone.
private struct JourneyStatusIndicator: View {
    let task: Task
    @Environment(\.defernoColors) private var colors

    private enum Tone { case normal, blocked }

    var body: some View {
        let active = Int(BridgeKt.journeyActiveSlot(task: task))
        let shelved = BridgeKt.journeyIsShelved(task: task)
        let blocked = BridgeKt.journeyIsBlocked(task: task)
        let token = BridgeKt.journeyLabelToken(task: task)
        // At the endpoints the middle shows a muted "IN-PROGRESS" hint ("not there yet"); otherwise it is
        // the reading's own label (IN-PROGRESS / IN-REVIEW / BLOCKED / NOT DOING).
        let atEnd = token == "TODO" || token == "DONE"
        let middleText = atEnd ? L.string("tasks_journey_in_progress") : L.journeyLabel(token)
        HStack(spacing: 3) {
            capsule(L.string("tasks_journey_todo"), on: active == 0, tone: .normal)
            connector(dashed: false)
            capsule(middleText, on: active == 1, tone: blocked ? .blocked : .normal, muted: atEnd)
            connector(dashed: shelved)
            capsule(L.string("tasks_journey_done"), on: active == 2, tone: .normal, struck: shelved)
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func capsule(_ text: String, on: Bool, tone: Tone, muted: Bool = false, struck: Bool = false) -> some View {
        let fg: Color = tone == .blocked ? colors.error : (on ? colors.onPrimary : (muted ? Color.secondary : colors.onSurfaceVariant))
        let bg: Color = tone == .blocked ? colors.errorContainer : (on ? colors.primary : colors.surfaceVariant)
        Text(text)
            .font(.caption2.weight(on ? .semibold : .regular))
            .strikethrough(struck)
            .padding(.horizontal, 6).padding(.vertical, 2)
            .background(bg, in: Capsule())
            .foregroundStyle(fg)
    }

    private func connector(dashed: Bool) -> some View {
        // Solid = on-track; dashed = the shelved "not headed to done" tail (an empty dash array is solid).
        DashLine()
            .stroke(colors.outlineVariant, style: StrokeStyle(lineWidth: 1, dash: dashed ? [2, 2] : []))
            .frame(width: 10, height: 1)
    }
}

/// A single horizontal hairline — the journey track's slot connector (solid or dashed via the stroke style).
private struct DashLine: Shape {
    func path(in rect: CGRect) -> Path {
        var p = Path()
        p.move(to: CGPoint(x: 0, y: rect.midY))
        p.addLine(to: CGPoint(x: rect.maxX, y: rect.midY))
        return p
    }
}

/// The status-picker sheet (ADR-0044) — the only path to change working state now (the inline chips are
/// gone). One `SelectableChip` per state via `WorkingState.ordered`, the current marked selected; a tap
/// forwards the intent and dismisses. Dropped is labelled "Set aside" by `WorkingState.label` (no shame).
private struct StatusPickerSheet: View {
    let current: WorkingState
    let onSelect: (WorkingState) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(L.string("tasks_detail_status_picker_title"))
                .font(.headline)
                .accessibilityAddTraits(.isHeader)
            ForEach(WorkingState.ordered, id: \.self) { state in
                SelectableChip(
                    label: state.label,
                    selected: state == current,
                    accessibilityLabel: state == current
                        ? L.format("tasks_detail_working_state_current_a11y", state.label)
                        : L.format("tasks_detail_set_working_state_a11y", state.label)
                ) { onSelect(state) }
            }
        }
        .padding(20)
        .frame(minWidth: 240, alignment: .leading)
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

/// A single muted line — the empty / loading placeholder inside a section.
private struct MutedLine: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.subheadline).foregroundStyle(.secondary) }
}

/// One Activity comment (ADR-0043): author + date, the body (or the encrypted placeholder), and — for the
/// current user's own comment — inline Edit / Delete. The server enforces the real authorization; `isMine`
/// (from the bridge) only chooses which affordances to show. Editing state is local so each row toggles alone.
private struct CommentRow: View {
    let comment: Comment
    let isMine: Bool
    let dateLabel: String
    let onEdit: (String, String) -> Void
    let onDelete: (String) -> Void
    @State private var editing = false
    @State private var draft = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text(isMine ? L.string("tasks_detail_comment_author_you") : L.string("tasks_detail_comment_author_member")).font(.caption.weight(.medium))
                Text(dateLabel).font(.caption2).foregroundStyle(.secondary)
            }
            if editing {
                TextField(L.string("tasks_detail_comment_button"), text: $draft, axis: .vertical)
                    .lineLimit(2...5)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    Button(L.string("common_cancel")) { editing = false }
                    Button(L.string("common_save")) {
                        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !trimmed.isEmpty { onEdit(comment.id, trimmed) }
                        editing = false
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            } else {
                Text(comment.body ?? L.string("tasks_detail_encrypted_comment")).font(.subheadline)
                if isMine {
                    HStack {
                        Button(L.string("common_edit")) { draft = comment.body ?? ""; editing = true }
                        Button(L.string("common_delete")) { onDelete(comment.id) }
                    }
                    .font(.subheadline)
                }
            }
        }
        .padding(.vertical, 6)
    }
}

/// A stable-identity wrapper for one ACTIVITY row. The sealed `ActivityItem` bridges to a Swift protocol
/// (an existential with no id-KeyPath), so `ForEach` keys on the bridge's stable id — the comment id or
/// "history:<index>" — instead of a positional index, keeping a CommentRow's edit `@State` bound to its
/// own comment across feed shifts (ADR-0043).
private struct ActivityRow: Identifiable {
    let id: String
    let item: ActivityItem
    init(_ item: ActivityItem) {
        self.id = BridgeKt.activityItemId(item: item)
        self.item = item
    }
}

/// One read-only item-history row (ADR-0043): a localized verb line + its date. History is server-recorded
/// and immutable — no affordances; it sits beside comments in the merged chronological feed.
private struct HistoryRow: View {
    let label: String
    let dateLabel: String

    var body: some View {
        HStack(spacing: 8) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Spacer()
            Text(dateLabel).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.vertical, 4)
    }
}

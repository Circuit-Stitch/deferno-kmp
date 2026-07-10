import AVFoundation
import Deferno
import SwiftUI
import UniformTypeIdentifiers

/// The Task detail pane (#207). Thin renderer of `TaskDetailComponent`: observes the hydrating row and
/// forwards the close / add-to-plan / working-state intents plus the web-parity detail sections —
/// editable Properties (Due/Labels), the recursive Subtasks tree, Attachments, and the Activity thread.
/// The component hydrates + loads comments/attachments on creation (#22); this View reflects its state.
struct TaskDetailView: View {
    let component: TaskDetailComponent
    /// false when the View is **pushed** onto the compact `NavigationStack`: the native bar supplies the
    /// back chevron (no title — ADR-0044), so the in-pane `DrilledBackBar` is dropped (it would be a second
    /// back control). The regular-width split keeps the default (the title-less `DrilledBackBar` is the
    /// column's only leading affordance; the connected-parent node is the heading).
    var showsHeader: Bool = true
    @StateObject private var state: StateFlowObserver<TaskDetailState>
    @State private var newSubtask = ""
    @State private var newLabel = ""
    @State private var commentDraft = ""
    @State private var importing = false
    // Kebab affordances (#262): the "Add subtask" prompt and the destructive Delete confirmation.
    @State private var showingAddSubtask = false
    @State private var kebabSubtask = ""
    @State private var confirmingDelete = false
    // Tabbed sections + read-only journey STATUS picker (ADR-0044): Info · Comments · History, and the
    // status sheet opened by tapping the STATUS row (the inline working-state chips are gone).
    @State private var tab: DetailTab = .info
    @State private var showingStatusPicker = false
    // Plays on-device brain-dump recordings (#272) over the bytes the bridge hands back — no network, no signed URL.
    @StateObject private var audioPlayer = OnDeviceAudioPlayer()
    @Environment(\.defernoColors) private var colors

    /// The three body tabs (ADR-0044): Info (description → add-to-plan → properties → subtasks →
    /// attachments), the per-item Comments feed, and the read-only per-item History (ADR-0043).
    enum DetailTab { case info, comments, history }

    init(component: TaskDetailComponent, showsHeader: Bool = true) {
        self.component = component
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            if showsHeader {
                // ADR-0044: the connected-parent node is the single heading, so the drilled bar is a
                // title-less Back affordance (the regular-width split / iPad-regular). On the compact push
                // the native NavigationStack bar owns Back, so this is dropped (showsHeader == false) and
                // no navigation title is set — the bar carries no title either.
                DrilledBackBar(onBack: { component.onCloseClicked() })
            }
            // Only while there's genuinely nothing on screen yet. Once the summary row is observed we
            // render it and let the background enrichment (description, #22) fill in silently — a
            // full-width strip flashing over an already-rendered task just shoves the body down and back
            // (the "loading blip" on content update).
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
        .background(colors.background)
        .paneNavigationTitle(nil)
    }

    @ViewBuilder
    private func taskBody(for task: Task, state value: TaskDetailState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // ADR-0044: the connected-parent header is the single heading — a muted, tappable parent
                // node thread-connected above the current item's title block, with the ⋮ overflow top-right.
                connectedParentHeader(task, state: value)

                // The three body tabs (Info default). The tab label names the section, so the in-tab
                // section headers that duplicated it (Activity/Properties) are dropped where redundant.
                Picker("", selection: $tab) {
                    Text(L.string("tasks_detail_tab_info")).tag(DetailTab.info)
                    Text(L.string("tasks_detail_tab_comments")).tag(DetailTab.comments)
                    Text(L.string("tasks_detail_tab_history")).tag(DetailTab.history)
                }
                .pickerStyle(.segmented)

                switch tab {
                case .info: infoTab(task, state: value)
                case .comments: commentsTab(value)
                case .history: historyTab(value)
                }
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
        // Tapping the read-only STATUS row opens the picker; selecting forwards the working-state intent.
        .sheet(isPresented: $showingStatusPicker) {
            StatusPickerSheet(current: task.workingState) {
                component.onSetWorkingState(target: $0)
                showingStatusPicker = false
            }
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { addAttachments(urls) }
        }
        // The kebab's "Add subtask" prompt: a calm inline title entry, mirroring the always-present
        // add-subtask field below — Add forwards a trimmed, non-empty title and clears.
        .alert(L.string("tasks_menu_add_subtask"), isPresented: $showingAddSubtask) {
            TextField(L.string("tasks_detail_subtask_title_label"), text: $kebabSubtask)
            Button(L.string("common_cancel"), role: .cancel) { kebabSubtask = "" }
            Button(L.string("common_add")) {
                let trimmed = kebabSubtask.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { component.onAddSubtask(title: trimmed) }
                kebabSubtask = ""
            }
        }
        // The destructive Delete, gated behind a confirmation (the kebab item is destructive).
        .confirmationDialog(
            L.string("tasks_detail_delete_confirm_title"),
            isPresented: $confirmingDelete,
            titleVisibility: .visible
        ) {
            Button(L.string("common_delete"), role: .destructive) { component.onDelete() }
            Button(L.string("common_cancel"), role: .cancel) {}
        } message: {
            Text(L.string("common_cannot_be_undone"))
        }
    }

    // MARK: - Connected-parent header + tabbed body (ADR-0044)

    /// The single heading (ADR-0044): the immediate parent (`Task.parentId` only — resolved on
    /// `TaskDetailState.parent`) as a muted, thread-connected node that **pushes the parent's detail** when
    /// tapped (back returns), sitting above the current item's title block. No parent → the item stands
    /// alone. The ⋮ overflow rides top-right of the current node.
    @ViewBuilder
    private func connectedParentHeader(_ task: Task, state value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            if let parent = value.parent {
                Button {
                    // TODO(port-verify): BridgeKt.openParent(component:parent:) — new bridge symbol (ADR-0044 §1h),
                    // forwards to component.onSubtaskClicked(parent.id). Confirm the SKIE selector on the next Xcode build.
                    BridgeKt.openParent(component: component, parent: parent)
                } label: {
                    HStack(spacing: 8) {
                        // The parent is resolved from the Task list ⇒ always a Task ⇒ a fixed kind chip.
                        TreeChip(text: L.string("common_kind_task"), tone: .neutral)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(parent.title)
                                .font(.subheadline)
                                .foregroundStyle(colors.onSurfaceVariant)
                                .lineLimit(1)
                            if let ref = parent.ref, !ref.isEmpty { MonoMeta(ref) }
                        }
                        Spacer(minLength: 0)
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .frame(minHeight: Layout.minTouchTarget)
                .accessibilityLabel(L.format("common_open_named_cd", parent.title))
                // The thin thread connector from the parent node down into the current item.
                Rectangle()
                    .fill(colors.outlineVariant)
                    .frame(width: 1.5, height: 10)
                    .padding(.leading, 10)
                    .accessibilityHidden(true)
            }
            HStack(alignment: .top, spacing: 8) {
                detailTitleBlock(task, state: value)
                    .frame(maxWidth: .infinity, alignment: .leading)
                overflowMenu
            }
        }
    }

    /// The Info tab: description (NOTES) → the Add-to-plan button → the properties table → the Subtasks
    /// outline → Attachments.
    @ViewBuilder
    private func infoTab(_ task: Task, state value: TaskDetailState) -> some View {
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
        Divider()
        attachmentsSection(value)
    }

    /// The Comments tab: the composer + this item's user comments (the `ActivityItem.Comment` arm).
    @ViewBuilder
    private func commentsTab(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            commentComposer(isPosting: value.isPostingComment)
            // Partition the merged feed: comments here, history in its own tab.
            let comments = value.activity.filter { BridgeKt.activityItemComment(item: $0) != nil }
            if value.commentsLoading && value.activity.isEmpty {
                MutedLine(L.string("common_loading"))
            } else if comments.isEmpty {
                MutedLine(L.string("tasks_detail_no_comments"))
            } else {
                // Key on the bridge's stable id, not the position (ADR-0043) — an optimistic delete above an
                // in-progress edit must not shift the draft onto the wrong comment.
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

    /// The History tab: this item's read-only server history (the `ActivityItem.HistoryEvent` arm, ADR-0043).
    @ViewBuilder
    private func historyTab(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            let history = value.activity.filter { BridgeKt.activityHistoryVerb(item: $0) != nil }
            if value.commentsLoading && value.activity.isEmpty {
                MutedLine(L.string("common_loading"))
            } else {
                // An empty history is a valid state (a fresh item) — render the (possibly empty) rows.
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

    // MARK: - Title block + overflow kebab (#262)

    /// The kind chip + headline title + mono meta line (ref · owner · time), plus — when the Task parents
    /// subtasks — an overall-status `<done> of <total>` progress bar right under the title, so the whole's
    /// completion is legible above the fold (#231). The kind here is always Task; it still wears the marker.
    @ViewBuilder
    private func detailTitleBlock(_ task: Task, state value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            TreeChip(text: L.string("common_kind_task"), tone: .accent)
            Text(task.title)
                .font(.title3.weight(.semibold))
                .foregroundStyle(colors.onSurface)
                .fixedSize(horizontal: false, vertical: true)
            let meta = [
                task.ref,
                BridgeKt.taskOwnerLabel(task: task),
                BridgeKt.taskTimeLabel(task: task),
            ]
            .compactMap { $0 }
            .filter { !$0.isEmpty && $0 != "—" }
            if !meta.isEmpty {
                MonoMeta(meta.joined(separator: "  ·  "))
            }
            if value.subtaskTotal > 0 {
                MonoMeta(L.format("tasks_progress_done", Int(value.subtaskDone), Int(value.subtaskTotal)))
                    .accessibilityLabel(L.format("tasks_detail_subtask_progress_a11y", Int(value.subtaskDone), Int(value.subtaskTotal)))
                ProgressBarThin(fraction: Double(value.subtaskDone) / Double(value.subtaskTotal))
                    .accessibilityHidden(true)
            }
        }
    }

    /// The detail's ⋮ more-actions kebab: Add subtask (prompts for a title) and the destructive Delete (the
    /// caller gates it behind a confirm). Icon-only trigger; self-describing label. ADR-0044 removed the
    /// "Set aside" item — Dropped is now reachable only through the STATUS picker sheet (the journey reading).
    private var overflowMenu: some View {
        Menu {
            // The on-device impediment flow (Deferno#525) — "what's stopping you?" over this stuck Task.
            Button(L.string("tasks_menu_break_this_down")) { component.onBreakdownClicked() }
            Button(L.string("tasks_menu_add_subtask")) { kebabSubtask = ""; showingAddSubtask = true }
            Button(L.string("common_delete"), role: .destructive) { confirmingDelete = true }
        } label: {
            DefernoIcon.moreVert.image
                .foregroundStyle(colors.onSurfaceVariant)
                .frame(width: Layout.minTouchTarget, height: Layout.minTouchTarget)
        }
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
            // OWNER stays a trailing read-only provenance row (ADR-0044 §8 — kept in the Info tab).
            propertyRow(label: L.string("tasks_detail_property_owner"), value: BridgeKt.taskOwnerLabel(task: task))
        }
    }

    /// The read-only journey STATUS row (ADR-0044): a display-only journey indicator (info-only, a11y
    /// hidden) whose row is tappable → opens the status picker sheet. The row itself carries the spoken
    /// label ("Status: <journey label>. Tap to change.") so VoiceOver isn't lost on the visual track.
    @ViewBuilder
    private func statusRow(_ task: Task) -> some View {
        // TODO(port-verify): BridgeKt.journeyLabelToken(task:) — new bridge symbol (ADR-0044 §1h); the token
        // maps 1:1 to JourneyLabel (TODO|IN_PROGRESS|IN_REVIEW|DONE|NOT_DOING|BLOCKED).
        let currentLabel = L.journeyLabel(BridgeKt.journeyLabelToken(task: task))
        Button { showingStatusPicker = true } label: {
            HStack {
                Text(L.string("tasks_detail_property_status"))
                    .font(.subheadline).foregroundStyle(.secondary)
                    .frame(width: 72, alignment: .leading)
                JourneyStatusIndicator(task: task)
                Spacer(minLength: 8)
                DefernoIcon.chevronRight.image(size: 14).foregroundStyle(.secondary)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .frame(minHeight: Layout.minTouchTarget)
        .accessibilityLabel(L.format("tasks_detail_status_row_a11y", currentLabel))
    }

    private func propertyRow(label: String, value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
            Text(value).font(.body).foregroundStyle(value == "—" ? .secondary : .primary)
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The editable WHEN row (ADR-0044 — renamed from DUE): a native compact `DatePicker` when a deadline is
    /// set (with a Clear button + a relative "N days away" suffix), else a "Set" button that seeds today's
    /// date. Confirming forwards the picked day; Clear forwards nil.
    @ViewBuilder
    private func dueRow(_ task: Task) -> some View {
        let hasDue = BridgeKt.taskDeadlineEpochSeconds(task: task) >= 0
        // TODO(port-verify): BridgeKt.taskDueRelativeToken/Count(task:) — new bridge symbols (ADR-0044 §1h);
        // token is null|TODAY|TOMORROW|YESTERDAY|DAYS_AWAY|DAYS_AGO, count is the day delta for the plural arms.
        let relToken = BridgeKt.taskDueRelativeToken(task: task)
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
                .datePickerStyle(.compact)
                if let relToken {
                    Text(L.relativeDay(relToken, Int(BridgeKt.taskDueRelativeCount(task: task))))
                        .font(.caption).foregroundStyle(.secondary)
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
                            .foregroundStyle(colors.onSurfaceVariant)
                            .background(colors.surfaceVariant, in: Capsule())
                        }
                    }
                }
            }
            addField(placeholder: L.string("tasks_detail_add_label_placeholder"), text: $newLabel) { entry in
                component.onSetLabels(labels: normalize(task.labels + [entry]))
            }
        }
    }

    /// Trim, drop blanks, de-dup — the same normalization the Android labels row applies before forwarding.
    private func normalize(_ list: [String]) -> [String] {
        var seen = Set<String>()
        return list.map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    // MARK: - Subtasks

    @ViewBuilder
    private func subtasksSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(L.string("tasks_detail_section_subtasks"), trailing: value.subtaskTotal > 0 ? "\(value.subtaskDone)/\(value.subtaskTotal)" : nil)
            if value.subtaskTotal > 0 {
                ProgressView(value: Double(value.subtaskDone), total: Double(value.subtaskTotal))
                    .accessibilityLabel(L.format("tasks_detail_subtask_progress_a11y", Int(value.subtaskDone), Int(value.subtaskTotal)))
                // "Hide done" filter (#197): drops Done rows from the outline; the progress bar keeps the full count.
                Toggle(isOn: Binding(
                    get: { value.hideDoneSubtasks },
                    set: { component.onSetHideDoneSubtasks(hide: $0) }
                )) {
                    HStack(spacing: 8) {
                        Text(L.string("tasks_detail_filter_hide_done"))
                        if value.hideDoneSubtasks {
                            // Shown/total — redundant compact badge, hidden from VoiceOver (the toggle carries it).
                            Text("\(value.subtaskTotal - value.subtaskDone)/\(value.subtaskTotal)")
                                .foregroundStyle(.secondary)
                                .accessibilityHidden(true)
                        }
                    }
                }
                .font(.subheadline)
            }
            ForEach(value.subtaskRows, id: \.task.stableKey) { row in
                subtaskRow(row)
            }
            addField(placeholder: L.string("tasks_detail_add_subtask_placeholder"), text: $newSubtask) { component.onAddSubtask(title: $0) }
        }
    }

    /// One depth-indented outline row. The subtree is pre-flattened by the component using the **same fold
    /// mechanism as the Tasks tree** (ADR-0034): the leading chevron toggles the shared device-local fold —
    /// a fold here re-flattens the Tasks tree too and survives restart — while a leaf keeps a fixed gutter
    /// so checkboxes align.
    private func subtaskRow(_ row: SubtaskRow) -> some View {
        let task = row.task
        let done = task.workingState == WorkingState.done
        return HStack(spacing: 8) {
            if row.hasChildren {
                Button { component.onToggleSubtaskExpand(id: task.stableKey, currentlyExpanded: row.isExpanded) } label: {
                    Image(systemName: row.isExpanded ? "chevron.down" : "chevron.right")
                        .font(.caption).foregroundStyle(.secondary).frame(width: 16)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(row.isExpanded ? L.format("common_collapse_named_cd", task.title) : L.format("common_expand_named_cd", task.title))
            } else {
                Spacer().frame(width: 16)
            }
            Button { component.onToggleSubtaskDone(subtask: task) } label: {
                Image(systemName: done ? "checkmark.square.fill" : "square")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(done ? L.format("common_mark_not_done_cd", task.title) : L.format("common_mark_done_cd", task.title))
            Button { BridgeKt.openSubtask(component: component, subtask: task) } label: {
                Text(task.title)
                    // Blocked mutes (but doesn't strike) like the tree row — "blocked, not finished" (#290/#292).
                    .strikethrough(done)
                    .foregroundStyle((done || task.blocked) ? .secondary : .primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.format("common_open_named_cd", task.title))
            // The "Blocked" pill is its own VoiceOver element (the row isn't combined).
            if task.blocked {
                TreeChip(text: L.string("common_blocked"), tone: .neutral)
            }
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
        }
        .padding(.leading, CGFloat(row.depth) * 20)
        .frame(minHeight: Layout.minTouchTarget)
    }

    // MARK: - Attachments

    @ViewBuilder
    private func attachmentsSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            // The count is synced + on-device combined (the brain-dump recording is on-device only — #272).
            SectionTitle(L.string("tasks_detail_section_attachments"), trailing: "\(value.attachments.count + value.onDeviceAttachments.count)")
            Button(value.isUploadingAttachment ? L.string("tasks_detail_uploading") : L.string("tasks_detail_add_file")) { importing = true }
                .font(.subheadline)
                .disabled(value.isUploadingAttachment)
            if value.attachments.isEmpty && value.onDeviceAttachments.isEmpty {
                Text(L.string("tasks_detail_no_attachments")).font(.subheadline).foregroundStyle(.secondary)
            } else {
                ForEach(value.attachments, id: \.id) { attachment in
                    AttachmentRow(
                        attachment: attachment,
                        onDelete: { component.onDeleteAttachment(attachmentId: $0) },
                        onSetCaption: { component.onSetAttachmentCaption(attachmentId: $0, caption: $1) }
                    )
                }
                // On-device rows (the retained brain-dump recording, #272): play/pause over local bytes + delete.
                // No signed-URL open and no caption editor — these never leave the device.
                ForEach(value.onDeviceAttachments, id: \.id) { attachment in
                    OnDeviceAttachmentRow(
                        attachment: attachment,
                        player: audioPlayer,
                        onPlayToggle: { togglePlay($0) },
                        onDelete: { id in
                            if audioPlayer.activeId == id { audioPlayer.stop() } // don't keep playing a deleted clip
                            component.onDeleteOnDeviceAttachment(attachmentId: id)
                        }
                    )
                }
            }
        }
    }

    /// Play/pause an on-device recording (#272). Same row already loaded → toggle pause/resume without re-reading
    /// bytes; a different row → fetch its bytes through the bridge and start it (which stops any current clip).
    private func togglePlay(_ attachment: OnDeviceAttachment) {
        if audioPlayer.activeId == attachment.id {
            audioPlayer.toggle()
        } else {
            BridgeKt.onDeviceAttachmentData(component: component, attachmentId: attachment.id) { data in
                if let data = data { audioPlayer.play(data as Data, id: attachment.id) }
            }
        }
    }

    // The web's attachment limits: at most 5 files per add, 25 MB each.
    private static let maxAttachments = 5
    private static let maxAttachmentBytes = 25 * 1024 * 1024

    /// Read each picked file's bytes and hand them to the component as `NSData` (the iOS twin of Android's
    /// SAF + `ContentResolver` read). `.fileImporter` returns security-scoped URLs, so bracket the read.
    private func addAttachments(_ urls: [URL]) {
        for url in urls.prefix(Self.maxAttachments) {
            let scoped = url.startAccessingSecurityScopedResource()
            defer { if scoped { url.stopAccessingSecurityScopedResource() } }
            guard let data = try? Data(contentsOf: url), data.count <= Self.maxAttachmentBytes else { continue }
            let mime = (try? url.resourceValues(forKeys: [.contentTypeKey]))?.contentType?.preferredMIMEType
                ?? "application/octet-stream"
            BridgeKt.addTaskAttachment(component: component, filename: url.lastPathComponent, contentType: mime, data: data)
        }
    }

    // MARK: - Activity / Comments

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

    /// A trailing-button inline "add" field, shared by the Labels and Subtasks rows. Submits a trimmed,
    /// non-empty entry then clears.
    @ViewBuilder
    private func addField(placeholder: String, text: Binding<String>, onAdd: @escaping (String) -> Void) -> some View {
        HStack {
            TextField(placeholder, text: text)
                .textFieldStyle(.roundedBorder)
                .submitLabel(.done)
                .onSubmit { submit(text, onAdd) }
            Button(L.string("common_add")) { submit(text, onAdd) }
                .disabled(text.wrappedValue.trimmingCharacters(in: .whitespaces).isEmpty)
        }
    }

    private func submit(_ text: Binding<String>, _ onAdd: (String) -> Void) {
        let trimmed = text.wrappedValue.trimmingCharacters(in: .whitespaces)
        if !trimmed.isEmpty { onAdd(trimmed); text.wrappedValue = "" }
    }
}

/// A calm section header: a heading title with an optional trailing count (e.g. "0/3", "2").
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

private struct MutedLine: View {
    let text: String
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.subheadline).foregroundStyle(.secondary) }
}

/// One attachment row: filename + size·type + optional caption (tap opens the signed URL), a Delete
/// button, and an inline caption editor. Local editing state lives on the row so each one toggles alone.
private struct AttachmentRow: View {
    let attachment: Attachment
    let onDelete: (String) -> Void
    let onSetCaption: (String, String) -> Void
    @State private var editing = false
    @State private var draft = ""
    @Environment(\.openURL) private var openURL

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Button {
                    if let url = URL(string: attachment.url) { openURL(url) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(attachment.filename).font(.body).lineLimit(1).truncationMode(.middle)
                        Text(L.format("tasks_detail_attachment_meta", byteLabel(attachment.size), attachment.mime))
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        if let caption = attachment.caption, !caption.isEmpty {
                            Text(caption).font(.subheadline)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.format("common_open_named_cd", attachment.filename))
                Button(L.string("common_delete")) { onDelete(attachment.id) }
                    .font(.subheadline)
                    .accessibilityLabel(L.format("tasks_detail_delete_attachment_a11y", attachment.filename))
            }
            if editing {
                TextField(L.string("tasks_detail_caption_placeholder"), text: $draft).textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    Button(L.string("common_cancel")) { editing = false }
                    Button(L.string("common_save")) {
                        let trimmed = draft.trimmingCharacters(in: .whitespaces)
                        if !trimmed.isEmpty { onSetCaption(attachment.id, trimmed) }
                        editing = false
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } else {
                Button(attachment.caption?.isEmpty == false ? L.string("tasks_detail_edit_caption") : L.string("tasks_detail_add_caption")) {
                    draft = attachment.caption ?? ""
                    editing = true
                }
                .font(.subheadline)
                .accessibilityLabel(L.format("tasks_detail_edit_caption_a11y", attachment.filename))
            }
        }
        .padding(.vertical, 6)
    }

    /// A friendly file size — the native `ByteCountFormatter`, the iOS counterpart to Android's `formatBytes`.
    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

/// One **on-device** attachment row (#272): the retained brain-dump recording, mirroring `AttachmentRow` minus
/// the signed-URL open + caption editor (these bytes never leave the device). A " · On device" marker sets it
/// apart from synced rows; **Play** shows only for audio, **Delete** removes the row + bytes.
private struct OnDeviceAttachmentRow: View {
    let attachment: OnDeviceAttachment
    @ObservedObject var player: OnDeviceAudioPlayer
    let onPlayToggle: (OnDeviceAttachment) -> Void
    let onDelete: (String) -> Void

    var body: some View {
        let isPlayingThis = player.activeId == attachment.id && player.isPlaying
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(attachment.filename).font(.body).lineLimit(1).truncationMode(.middle)
                Text(L.format("tasks_detail_attachment_meta_on_device", byteLabel(attachment.size), attachment.mime))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if let caption = attachment.caption, !caption.isEmpty {
                    Text(caption).font(.subheadline)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if attachment.isAudio {
                Button(isPlayingThis ? L.string("common_pause") : L.string("tasks_detail_play")) { onPlayToggle(attachment) }
                    .font(.subheadline)
                    .accessibilityLabel("\(isPlayingThis ? "Pause" : "Play") \(attachment.filename)")
            }
            Button(L.string("common_delete")) { onDelete(attachment.id) }
                .font(.subheadline)
                .accessibilityLabel(L.format("tasks_detail_delete_attachment_a11y", attachment.filename))
        }
        .padding(.vertical, 6)
    }

    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

/// Plays one on-device brain-dump recording (#272) from in-memory bytes via `AVAudioPlayer`, retained here so it
/// isn't deallocated mid-clip. `activeId`/`isPlaying` drive the row's Play/Pause label; the delegate resets them
/// when the clip ends. Sets the session to `.playback` (the brain-dump recorder leaves it on `.record`).
final class OnDeviceAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    /// The id of the clip currently loaded (playing or paused), or nil when nothing is loaded.
    @Published private(set) var activeId: String?
    @Published private(set) var isPlaying = false
    private var player: AVAudioPlayer?

    /// Load + start a clip (replacing any current one).
    func play(_ data: Data, id: String) {
        try? AVAudioSession.sharedInstance().setCategory(.playback, mode: .default)
        try? AVAudioSession.sharedInstance().setActive(true)
        player = try? AVAudioPlayer(data: data)
        player?.delegate = self
        activeId = id
        player?.play()
        isPlaying = player?.isPlaying ?? false
    }

    /// Pause if playing / resume if paused — same loaded clip, no re-read.
    func toggle() {
        guard let player else { return }
        if player.isPlaying { player.pause(); isPlaying = false } else { player.play(); isPlaying = true }
    }

    /// Stop + unload (e.g. the playing row was deleted).
    func stop() {
        player?.stop()
        player = nil
        isPlaying = false
        activeId = nil
    }

    func audioPlayerDidFinishPlaying(_ player: AVAudioPlayer, successfully flag: Bool) {
        isPlaying = false
        activeId = nil
    }
}

/// One Activity comment: author + date, the body, and (for the current user's own) inline Edit / Delete.
/// The server enforces the real authorization; this only chooses which affordances to show.
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

/// One read-only ACTIVITY history entry (ADR-0043): a localized change label over its muted date. Mirrors
/// `CommentRow`'s muted styling and vertical rhythm, minus the author line and the edit / delete
/// affordances — server-authored history is never editable.
private struct HistoryRow: View {
    let label: String
    let dateLabel: String

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label).font(.subheadline).foregroundStyle(.secondary)
            Text(dateLabel).font(.caption2).foregroundStyle(.secondary)
        }
        .padding(.vertical, 6)
    }
}

/// A title-less drilled Back bar (ADR-0044): the connected-parent node is the heading, so the bar carries
/// only a leading Back affordance. It renders on the regular-width split / iPad-regular column; on the
/// compact push the native `NavigationStack` bar owns Back and this is dropped (`showsHeader == false`).
/// A `nil` `onBack` hides the control (the macOS-inline root case), matching `PaneHeader`'s optional back.
private struct DrilledBackBar: View {
    let onBack: (() -> Void)?

    @Environment(\.defernoColors) private var colors

    var body: some View {
        HStack(spacing: 8) {
            if let onBack {
                Button(action: onBack) {
                    HStack(spacing: 4) {
                        DefernoIcon.chevronLeft.image(size: 16)
                        Text(L.string("common_back"))
                    }
                }
                .frame(minHeight: Layout.minTouchTarget)
                .accessibilityLabel(L.string("common_back"))
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 8)
        .frame(minHeight: 48)
        .background(colors.surface)
    }
}

/// The read-only journey STATUS indicator (ADR-0044): a display-only three-slot track — initial `TO-DO`,
/// a present middle marker, terminal `DONE` — rendered from the journey **reading** (`WorkingState` +
/// the orthogonal, server-derived `blocked` flag) over the bridge. `NOT DOING` (Dropped) reads as a
/// middle marker with a dashed tail to a struck-through `DONE`; `BLOCKED` tones the middle with the error
/// colour. Colour is reinforcement only — each slot carries text — and the whole track is a single
/// a11y-hidden element (the STATUS row speaks the current label).
private struct JourneyStatusIndicator: View {
    let task: Task

    @Environment(\.defernoColors) private var colors

    var body: some View {
        // TODO(port-verify): BridgeKt.journeyActiveSlot / journeyLabelToken / journeyIsShelved /
        // journeyIsBlocked(task:) — new bridge symbols (ADR-0044 §1h) reading the shared journeyStatus().
        let activeSlot = Int(BridgeKt.journeyActiveSlot(task: task))   // Initial=0, Middle=1, Terminal=2
        let token = BridgeKt.journeyLabelToken(task: task)
        let shelved = BridgeKt.journeyIsShelved(task: task)
        let blocked = BridgeKt.journeyIsBlocked(task: task)
        // When the reading is TO-DO/DONE the middle shows a muted IN-PROGRESS "not there yet" hint.
        let middleToken = (token == "TODO" || token == "DONE") ? "IN_PROGRESS" : token
        HStack(spacing: 4) {
            slot(L.journeyLabel("TODO"), active: activeSlot == 0, color: colors.primary)
            connector(dashed: shelved)
            slot(L.journeyLabel(middleToken), active: activeSlot == 1, color: blocked ? colors.error : (shelved ? colors.inkMuted : colors.primary))
            connector(dashed: shelved)
            slot(L.journeyLabel("DONE"), active: activeSlot == 2, color: colors.primary, struck: shelved)
        }
        .accessibilityHidden(true)
    }

    @ViewBuilder
    private func slot(_ label: String, active: Bool, color: Color, struck: Bool = false) -> some View {
        Text(label)
            .font(.defernoMono(9, weight: .semibold))
            .tracking(0.6)
            .strikethrough(struck)
            .foregroundStyle(active ? colors.onPrimary : color)
            .padding(.horizontal, 8).padding(.vertical, 4)
            .background(active ? color : colors.surfaceVariant, in: Capsule())
    }

    private func connector(dashed: Bool) -> some View {
        JourneyConnectorLine()
            .stroke(colors.outlineVariant, style: StrokeStyle(lineWidth: 1.5, dash: dashed ? [3, 2] : []))
            .frame(width: 12, height: 2)
    }
}

/// A single horizontal rule for the journey track's connector (solid or dashed via the caller's stroke).
private struct JourneyConnectorLine: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: 0, y: rect.midY))
        path.addLine(to: CGPoint(x: rect.width, y: rect.midY))
        return path
    }
}

/// The status picker sheet (ADR-0044): the state change moved off the removed inline chips onto a modal
/// list over the five `WorkingState`s (the app's plain labels), the current one marked. Selecting forwards
/// the working-state intent; the presenter clears `showingStatusPicker`.
private struct StatusPickerSheet: View {
    let current: WorkingState
    let onSelect: (WorkingState) -> Void

    @Environment(\.defernoColors) private var colors

    var body: some View {
        NavigationStack {
            List {
                ForEach(WorkingState.ordered, id: \.self) { state in
                    Button { onSelect(state) } label: {
                        HStack {
                            Text(state.label).foregroundStyle(colors.onSurface)
                            Spacer()
                            if state == current {
                                DefernoIcon.check.image(size: 16).foregroundStyle(colors.primary)
                            }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .frame(minHeight: Layout.minTouchTarget)
                    .accessibilityAddTraits(state == current ? [.isButton, .isSelected] : .isButton)
                }
            }
            .navigationTitle(L.string("tasks_detail_status_picker_title"))
        }
    }
}

import AVFoundation
import Deferno
import SwiftUI
import UIKit
import UniformTypeIdentifiers

/// The Task detail pane (#207 / ADR-0044 / ADR-0046). Thin renderer of `TaskDetailComponent`: observes the
/// hydrating row and forwards the close / add-to-plan / working-state intents plus the web-parity detail
/// sections. Since ADR-0046 the body is **two tabs** — `Info` (the connected-parent header, NOTES markdown,
/// the properties table, and the subtask tree) and `Trail` (the one merged, day-grouped comments +
/// enriched-history feed with a tappable old→new `ChangeDiffSheet`). The task's top actions — Add subtask ·
/// Add comment · Add to today's plan · Change status — live on an overlaid FAB + add sheet (ADR-0044 parity
/// with Android). The component hydrates + loads comments/attachments on creation (#22); this View reflects its state.
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
    // Focuses the Trail comment composer when the FAB add sheet's "Add comment" fires — the iOS twin of
    // Android's revealCommentComposer (switch to the Trail tab + pop the keyboard ready to type).
    @FocusState private var composerFocused: Bool
    @State private var importing = false
    // The "Add subtask" title prompt (opened from the FAB add sheet — ADR-0044 parity, #262) and the
    // destructive Delete confirmation (still the kebab).
    @State private var showingAddSubtask = false
    @State private var addSubtaskTitle = ""
    @State private var confirmingDelete = false
    // Tabbed sections + read-only journey STATUS picker (ADR-0044/ADR-0046): Info · Trail, the status sheet
    // opened by tapping the STATUS row, the "View attachments" sheet, and the tapped-history change-diff sheet.
    @State private var tab: DetailTab = .info
    @State private var showingStatusPicker = false
    @State private var showingAttachmentsSheet = false
    // The FAB's add-actions sheet (ADR-0044 parity): the floating "+" opens a bottom sheet of the task's top
    // actions — Add subtask · Add comment · Add to today's plan · Change status — the native ModalBottomSheet
    // twin of Android's FAB + add sheet (which replaced the inline add-to-plan button).
    @State private var showingAddSheet = false
    // The action chosen from the add sheet, fired AFTER it dismisses (its onDismiss) so a follow-on
    // presentation — the status picker sheet, the add-subtask alert — never races the add sheet's own
    // dismissal (no sheet-over-sheet). nil when the sheet closed without a choice.
    @State private var pendingAddAction: AddAction?
    // Bumped when "Add to today's plan" fires so the confirmation toast re-arms + shows (Android's Toast
    // twin — a transient acknowledgement, since today's plan isn't shown on this screen).
    @State private var addedToPlanToastToken = 0
    // The tapped diff-carrying history row (#260) — its ChangeDiffSheet, presented via `.sheet(item:)`.
    @State private var openDiff: DiffPresentation?
    // Plays on-device brain-dump recordings (#272) over the bytes the bridge hands back — no network, no signed URL.
    @StateObject private var audioPlayer = OnDeviceAudioPlayer()
    @Environment(\.defernoColors) private var colors
    @Environment(\.openURL) private var openURL

    /// The two body tabs (ADR-0046): Info (connected-parent header → NOTES → add-to-plan → properties →
    /// subtasks) and Trail (the merged, day-grouped comments + enriched-history feed).
    enum DetailTab { case info, trail }

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
            // render it and let the background enrichment (description, #22) fill in silently.
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
        // Reset to the Info tab (and drop any open diff) when drilling into a different item (ADR-0046) —
        // the detail View is reused across parent→subtask navigation, so an un-reset tab would carry over.
        .onChange(of: BridgeKt.detailKey(component: component)) { _ in
            tab = .info
            openDiff = nil
        }
    }

    @ViewBuilder
    private func taskBody(for task: Task, state value: TaskDetailState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // ADR-0044: the connected-parent header is the single heading — a muted, tappable parent
                // node thread-connected above the current item's title block, with the ⋮ overflow top-right.
                connectedParentHeader(task, state: value)

                // The two body tabs (Info default). The tab label names the section, so the in-tab section
                // headers that duplicated it are dropped where redundant.
                Picker("", selection: $tab) {
                    Text(L.string("tasks_detail_tab_info")).tag(DetailTab.info)
                    Text(L.string("tasks_detail_tab_trail")).tag(DetailTab.trail)
                }
                .pickerStyle(.segmented)

                switch tab {
                case .info: infoTab(task, state: value)
                case .trail: trailSection(value)
                }
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
            // Keep the last row clear of the overlaid FAB (Android pads the tail the same way).
            .padding(.bottom, 72)
        }
        // The overlaid add-actions FAB (ADR-0044 parity): opens the add sheet below. Bottom-trailing over the
        // scroll body; the trailing bottom padding above keeps it off the last row.
        .overlay(alignment: .bottomTrailing) { addFab }
        // The "Added to today's plan" confirmation toast (Android's Toast twin): rides above the FAB when the
        // add sheet's "Add to today's plan" fires, then auto-dismisses. Bumping addedToPlanToastToken re-arms it.
        .overlay(alignment: .bottom) {
            ConfirmationToast(message: L.string("breakdown_msg_added_to_plan"), token: addedToPlanToastToken)
                .padding(.horizontal, Layout.gutter)
                .padding(.bottom, 96)
        }
        // The FAB's add sheet: the task's top actions as a bottom sheet (the native ModalBottomSheet twin —
        // .sheet + detents, the same idiom this PR uses for ChangeDiffSheet). Each row records its choice and
        // dismisses; the choice fires in onDismiss so a follow-on (status picker sheet / add-subtask alert)
        // never races the sheet's own dismissal.
        .sheet(isPresented: $showingAddSheet, onDismiss: firePendingAddAction) {
            AddActionsSheet { pendingAddAction = $0; showingAddSheet = false }
        }
        // Tapping the read-only STATUS row opens the picker; selecting forwards the working-state intent.
        .sheet(isPresented: $showingStatusPicker) {
            StatusPickerSheet(current: task.workingState) {
                component.onSetWorkingState(target: $0)
                showingStatusPicker = false
            }
        }
        // "View" on the ATTACHMENTS row opens the full manage list (playback / delete / caption).
        .sheet(isPresented: $showingAttachmentsSheet) {
            AttachmentsSheet(
                attachments: value.attachments,
                onDeviceAttachments: value.onDeviceAttachments,
                player: audioPlayer,
                onDelete: { component.onDeleteAttachment(attachmentId: $0) },
                onSetCaption: { component.onSetAttachmentCaption(attachmentId: $0, caption: $1) },
                onPlayToggle: { togglePlay($0) },
                onDeleteOnDevice: { id in
                    if audioPlayer.activeId == id { audioPlayer.stop() }
                    component.onDeleteOnDeviceAttachment(attachmentId: id)
                }
            )
        }
        // A tapped diff-carrying history row (#260): the calm old→new change diff for that recorded edit.
        // The shared ChangeDiffSheet (DesignSystem/) is presentational — resolve the row's enriched title,
        // meta line, and diff rows here (the Trail is already inside the item, so no note / Open-item action).
        .sheet(item: $openDiff) { presentation in
            let line = BridgeKt.activityHistoryLine(item: presentation.item)
            ChangeDiffSheet(
                title: line.map { L.historyEnriched($0) } ?? "",
                subtitle: TrailDateFormat.whenLabel(BridgeKt.activityItemEpochSeconds(item: presentation.item)),
                rows: BridgeKt.activityHistoryDiffRows(item: presentation.item)
            )
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { addAttachments(urls) }
        }
        // The add sheet's "Add subtask" prompt: a calm inline title entry, mirroring the always-present
        // add-subtask field below — Add forwards a trimmed, non-empty title and clears.
        .alert(L.string("tasks_menu_add_subtask"), isPresented: $showingAddSubtask) {
            TextField(L.string("tasks_detail_subtask_title_label"), text: $addSubtaskTitle)
            Button(L.string("common_cancel"), role: .cancel) { addSubtaskTitle = "" }
            Button(L.string("common_add")) {
                let trimmed = addSubtaskTitle.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { component.onAddSubtask(title: trimmed) }
                addSubtaskTitle = ""
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

    // MARK: - Connected-parent header (ADR-0044): amber git-graph BranchCell

    /// The single heading (ADR-0044): the immediate parent (`TaskDetailState.parent`) as a muted,
    /// git-graph-connected node that **pushes the parent's detail** when tapped (back returns), sitting above
    /// the current item's title block via the amber `BranchCell` thread. No parent → the item stands alone.
    /// The ⋮ overflow rides top-right of the current node.
    @ViewBuilder
    private func connectedParentHeader(_ task: Task, state value: TaskDetailState) -> some View {
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 0) {
                if let parent = value.parent {
                    // The parent node + its title (muted, tappable) — the branch's upper node descends from here.
                    Button {
                        BridgeKt.openParent(component: component, parent: parent)
                    } label: {
                        HStack(spacing: 0) {
                            BranchCell(isCurrent: false, color: colors.primary)
                            Text(parent.title)
                                .font(.subheadline)
                                .foregroundStyle(colors.onSurfaceVariant)
                                .lineLimit(1)
                                .frame(maxWidth: .infinity, alignment: .leading)
                            if let ref = shortRef(parent.ref) { MonoMeta(ref) }
                        }
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .frame(minHeight: Layout.minTouchTarget)
                    .accessibilityLabel(L.format("common_open_named_cd", parent.title))
                    // The current item's node (the branch's lower node) beside its title block.
                    HStack(alignment: .top, spacing: 0) {
                        BranchCell(isCurrent: true, color: colors.primary)
                        detailTitleBlock(task, state: value)
                    }
                } else {
                    detailTitleBlock(task, state: value)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            overflowMenu
        }
    }

    /// The title block (ADR-0044): the Task title at headline rank with a dimmed `[GitHub#N]` external-ref
    /// prefix, a mono meta line (the short `#N` ref), and — when the Task parents subtasks — an overall-status
    /// `{done} of {total}` progress bar right under the title (#231). The kind is carried by the connected
    /// branch node, not a chip.
    @ViewBuilder
    private func detailTitleBlock(_ task: Task, state value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(titleWithExternalRef(task))
                .font(.title3.weight(.semibold))
                .fixedSize(horizontal: false, vertical: true)
            if let ref = shortRef(task.ref) {
                MonoMeta(ref)
            }
            if value.subtaskTotal > 0 {
                MonoMeta(L.format("tasks_progress_done", Int(value.subtaskDone), Int(value.subtaskTotal)))
                    .accessibilityLabel(L.format("tasks_detail_subtask_progress_a11y", Int(value.subtaskDone), Int(value.subtaskTotal)))
                ProgressBarThin(fraction: Double(value.subtaskDone) / Double(value.subtaskTotal))
                    .accessibilityHidden(true)
            }
        }
    }

    /// The Task title with its dimmed `[GitHub#N]` external-ref prefix (ADR-0044): the tracker owns the title,
    /// so the prefix is derived client-side (`BridgeKt.taskExternalRefPrefix`) and rendered muted before it.
    private func titleWithExternalRef(_ task: Task) -> AttributedString {
        var title = AttributedString(task.title)
        title.foregroundColor = colors.onSurface
        guard let prefix = BridgeKt.taskExternalRefPrefix(task: task) else { return title }
        var prefixAttr = AttributedString(prefix + " ")
        prefixAttr.foregroundColor = colors.inkMuted
        return prefixAttr + title
    }

    /// The short human ref (`#123`) from a full `{org}-{sequence}` ref: the trailing segment, rendered
    /// `#N` when it is the numeric sequence, else the ref verbatim; `nil` for a just-created (ref-less) row.
    private func shortRef(_ ref: String?) -> String? {
        guard let ref, !ref.isEmpty else { return nil }
        let tail = ref.split(separator: "-").last.map(String.init) ?? ref
        if !tail.isEmpty && tail.allSatisfy(\.isNumber) { return "#\(tail)" }
        return ref
    }

    /// The detail's ⋮ more-actions kebab: Break this down and the destructive Delete (gated behind a
    /// confirm). "Add subtask" moved to the FAB add sheet — parity with Android dropping it from the kebab
    /// once the FAB owns the add actions (externalAddActions). Icon-only trigger; self-describing label.
    private var overflowMenu: some View {
        Menu {
            Button(L.string("tasks_menu_break_this_down")) { component.onBreakdownClicked() }
            Button(L.string("common_delete"), role: .destructive) { confirmingDelete = true }
        } label: {
            DefernoIcon.moreVert.image
                .foregroundStyle(colors.onSurfaceVariant)
                .frame(width: Layout.minTouchTarget, height: Layout.minTouchTarget)
        }
        .accessibilityLabel(L.string("tasks_detail_more_actions"))
    }

    /// The overlaid add-actions FAB (ADR-0044 parity with Android's FAB + ModalBottomSheet): a floating "+"
    /// bottom-trailing over the task body, opening the add sheet ([showingAddSheet]). Replaces the inline
    /// "Add to today's plan" button — the sheet now owns that action alongside Add subtask / Add comment /
    /// Change status. The fill is the strong `primary` accent (matching the app's other primary CTAs — the
    /// drawer's New task, the removed inline add-to-plan button), a deliberate iOS choice over Android's
    /// softer M3 `primaryContainer` FAB default (the iOS palette has no container tone).
    private var addFab: some View {
        Button { showingAddSheet = true } label: {
            DefernoIcon.plus.image(size: 24, weight: .semibold)
                .foregroundStyle(colors.onPrimary)
                .frame(width: 56, height: 56)
                .background(colors.primary, in: Circle())
                .shadow(color: .black.opacity(0.18), radius: 8, x: 0, y: 3)
        }
        .buttonStyle(.plain)
        .padding(.trailing, 20)
        .padding(.bottom, 24)
        .accessibilityLabel(L.string("common_add"))
    }

    /// Fire the add sheet's chosen action once the sheet has fully dismissed (its onDismiss) — deferring any
    /// follow-on presentation (the status picker sheet / the add-subtask alert) past the sheet's own dismissal
    /// so the two never overlap. Comment/plan carry no presentation, so they simply run here too.
    private func firePendingAddAction() {
        guard let action = pendingAddAction else { return }
        pendingAddAction = nil
        switch action {
        case .subtask: addSubtaskTitle = ""; showingAddSubtask = true
        // Switch to the Trail tab, then focus the composer one runloop later — the composer only composes once
        // the tab flips, so a same-tick focus wouldn't land (Android's revealCommentComposer focus twin).
        case .comment:
            tab = .trail
            DispatchQueue.main.async { composerFocused = true }
        // Add to today's plan, then surface a transient confirmation (the plan isn't shown here): the toast
        // below for sighted users + a VoiceOver announcement — the iOS twins of Android's Toast.
        case .plan:
            component.onAddToPlanClicked()
            addedToPlanToastToken += 1
            UIAccessibility.post(notification: .announcement, argument: L.string("breakdown_msg_added_to_plan"))
        case .status: showingStatusPicker = true
        }
    }

    // MARK: - Info tab: NOTES → properties table → subtasks

    /// The Info tab body: the NOTES markdown, the properties table
    /// (WHEN · STATUS · LABELS · OWNER · SOURCE · ATTACHMENTS), and the Subtasks outline.
    /// ("Add to today's plan" moved to the FAB add sheet — ADR-0044 parity with Android.)
    @ViewBuilder
    private func infoTab(_ task: Task, state value: TaskDetailState) -> some View {
        let hasDescription = (task.description_?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
        // NOTES: a caps section header over the (markdown) description, or a muted "no description" once
        // hydration settles. The header hides only during the brief pre-hydration gap.
        if hasDescription || !value.isHydrating {
            SectionLabel(L.string("new_notes_label"))
        }
        if hasDescription, let description = task.description_ {
            MarkdownDescription(markdown: description, sheetTitle: L.string("new_notes_label"))
        } else if !value.isHydrating {
            Text(L.string("tasks_detail_no_description"))
                .font(.callout)
                .foregroundStyle(colors.inkMuted)
        }
        // "Add to today's plan" moved to the FAB add sheet (ADR-0044 parity: Android replaced the inline
        // button with a FAB + action sheet). The Info tab now runs NOTES → properties → subtasks.
        propertiesTable(task, state: value)
        subtasksSection(value)
    }

    // MARK: - Properties table (WHEN · STATUS · LABELS · OWNER · SOURCE · ATTACHMENTS)

    /// The properties table (ADR-0044): a tinted small-caps label column ruled off from the content, rows
    /// divided by hairlines, wrapped in a rounded border. Only the rows this item actually carries appear —
    /// WHEN drops with no deadline, OWNER only for a shared/multi-group account, SOURCE only when imported.
    private func propertiesTable(_ task: Task, state value: TaskDetailState) -> some View {
        var rows: [AnyView] = []

        // WHEN — only when a deadline is set.
        if BridgeKt.taskDeadlineEpochSeconds(task: task) >= 0 {
            rows.append(AnyView(
                PropertyTableRow(label: L.string("tasks_detail_property_when")) { dueCell(task) }
            ))
        }
        // STATUS — always; the whole row opens the status picker sheet.
        let statusLabel = L.journeyLabel(BridgeKt.journeyLabelToken(task: task))
        rows.append(AnyView(
            PropertyTableRow(
                label: L.string("tasks_detail_property_status"),
                onTap: { showingStatusPicker = true },
                rowA11y: L.format("tasks_detail_status_row_a11y", statusLabel)
            ) { JourneyStatusIndicator(task: task) }
        ))
        // LABELS — always.
        rows.append(AnyView(
            PropertyTableRow(label: L.string("common_labels")) { labelsCell(task) }
        ))
        // OWNER — only for a shared / multi-group account (the bridge returns "—" when there's no owning org).
        let ownerLabel = BridgeKt.taskOwnerLabel(task: task)
        if value.ownerGroupCount > 1 && ownerLabel != "—" {
            rows.append(AnyView(
                PropertyTableRow(label: L.string("tasks_detail_property_owner")) {
                    Text(ownerLabel).font(.body).foregroundStyle(colors.onSurface)
                }
            ))
        }
        // SOURCE — only for a synced / imported item.
        if let providerToken = BridgeKt.taskSourceProviderToken(task: task) {
            rows.append(AnyView(
                PropertyTableRow(label: L.string("tasks_detail_property_source")) {
                    sourceCell(task, providerToken: providerToken)
                }
            ))
        }
        // ATTACHMENTS — always (it holds the "Add file" affordance even with no files yet).
        rows.append(AnyView(
            PropertyTableRow(label: L.string("tasks_detail_section_attachments")) { attachmentsCell(value) }
        ))

        return VStack(spacing: 0) {
            ForEach(rows.indices, id: \.self) { i in
                if i > 0 { Divider().overlay { colors.outlineVariant } }
                rows[i]
            }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .overlay(RoundedRectangle(cornerRadius: 12).stroke(colors.outlineVariant, lineWidth: 1))
    }

    /// The WHEN cell: a compact `DatePicker` over the deadline day + a relative-day suffix + a Clear button.
    /// Confirming forwards the picked day; Clear forwards nil. (Only rendered when a deadline is set.)
    @ViewBuilder
    private func dueCell(_ task: Task) -> some View {
        let relToken = BridgeKt.taskDueRelativeToken(task: task)
        HStack {
            DatePicker(
                "",
                selection: Binding(
                    get: { Date(timeIntervalSince1970: BridgeKt.taskDeadlineEpochSeconds(task: task)) },
                    set: { BridgeKt.setTaskDeadline(component: component, epochSeconds: $0.timeIntervalSince1970) }
                ),
                displayedComponents: .date
            )
            .labelsHidden()
            .datePickerStyle(.compact)
            if let relToken {
                Text(L.relativeDay(relToken, Int(BridgeKt.taskDueRelativeCount(task: task))))
                    .font(.caption)
                    .foregroundStyle(colors.inkMuted)
            }
            Spacer(minLength: 0)
            Button(L.string("common_clear")) { BridgeKt.clearTaskDeadline(component: component) }
                .font(.subheadline)
                .accessibilityLabel(L.string("tasks_detail_clear_due_date_a11y"))
        }
    }

    /// The LABELS cell: the labels as removable chips + an inline "add label" field. Any add/remove forwards
    /// the whole normalized list (the component replaces the Task's labels wholesale).
    @ViewBuilder
    private func labelsCell(_ task: Task) -> some View {
        VStack(alignment: .leading, spacing: 6) {
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

    /// The SOURCE cell: the provider mark + the origin label (`owner/repo#N` when present, else the provider
    /// name), underlined + tappable when the provenance carries a URL. Read-only — provenance, not an editor.
    @ViewBuilder
    private func sourceCell(_ task: Task, providerToken: String) -> some View {
        let origin = BridgeKt.taskSourceOriginLabel(task: task) ?? ""
        let source: ItemSource = providerToken == "GITHUB" ? ItemSource.gitHub : ItemSource.googleCalendar
        HStack(spacing: 6) {
            SourceMark(source: source)
            if let urlString = BridgeKt.taskSourceUrl(task: task), let url = URL(string: urlString) {
                Button { openURL(url) } label: {
                    Text(origin).underline().foregroundStyle(colors.onSurface)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.format("common_open_named_cd", origin))
            } else {
                Text(origin).foregroundStyle(colors.onSurface)
            }
            Spacer(minLength: 0)
        }
    }

    /// The ATTACHMENTS cell: a compact count + combined size summary over **View** (opens the full manage
    /// sheet) and **Add file** (the file picker). Keeping the list off the row lets attachments live in the
    /// narrow value column like the other properties.
    @ViewBuilder
    private func attachmentsCell(_ value: TaskDetailState) -> some View {
        let count = value.attachments.count + value.onDeviceAttachments.count
        VStack(alignment: .leading, spacing: 4) {
            if count == 0 {
                Text(L.string("tasks_detail_no_attachments"))
                    .font(.subheadline)
                    .foregroundStyle(colors.inkMuted)
            } else {
                let totalBytes = value.attachments.reduce(Int64(0)) { $0 + $1.size }
                    + value.onDeviceAttachments.reduce(Int64(0)) { $0 + $1.size }
                Text(L.plural("tasks_detail_attachment_count", count))
                    .foregroundStyle(colors.onSurface)
                MonoMeta(L.format("tasks_detail_attachments_total", byteLabel(totalBytes)))
            }
            HStack(spacing: 12) {
                if count > 0 {
                    Button(L.string("tasks_detail_attachments_view")) { showingAttachmentsSheet = true }
                        .font(.subheadline)
                }
                Button(value.isUploadingAttachment ? L.string("tasks_detail_uploading") : L.string("tasks_detail_add_file")) {
                    importing = true
                }
                .font(.subheadline)
                .disabled(value.isUploadingAttachment)
            }
        }
    }

    /// A friendly file size — the native `ByteCountFormatter`, the iOS counterpart to Android's `formatBytes`.
    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    // MARK: - Subtasks (tree-rail filigree)

    /// The subtask outline (#231): a done/total count + progress bar, a "Hide done" filter, the connected
    /// tree-rail rows, and an "add subtask" field. The rows are pre-flattened by the component with the same
    /// fold mechanism as the Tasks tree (ADR-0034), so a fold here re-flattens the Tasks tree too.
    @ViewBuilder
    private func subtasksSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionLabel(
                value.subtaskTotal > 0
                    ? "\(L.string("tasks_detail_section_subtasks")) · \(L.format("tasks_progress_fraction", Int(value.subtaskDone), Int(value.subtaskTotal)))"
                    : L.string("tasks_detail_section_subtasks")
            )
            if value.subtaskTotal > 0 {
                ProgressBarThin(fraction: Double(value.subtaskDone) / Double(value.subtaskTotal))
                    .accessibilityLabel(L.format("tasks_detail_subtask_progress_a11y", Int(value.subtaskDone), Int(value.subtaskTotal)))
                // "Hide done" filter (#197): drops Done rows from the outline; the progress bar keeps the full count.
                Toggle(isOn: Binding(
                    get: { value.hideDoneSubtasks },
                    set: { component.onSetHideDoneSubtasks(hide: $0) }
                )) {
                    HStack(spacing: 8) {
                        Text(L.string("tasks_detail_filter_hide_done"))
                        if value.hideDoneSubtasks {
                            Text(L.format("tasks_progress_fraction", Int(value.subtaskTotal - value.subtaskDone), Int(value.subtaskTotal)))
                                .foregroundStyle(colors.inkMuted)
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

    /// One connected-tree subtask row (#231): the curvy `TreeRail` + kind-node fold disc (the same filigree as
    /// the Tasks tree, derived off `SubtaskRow.spine`/`depth`), then the round done toggle, a drill-in title
    /// (blocked mutes without a strike — "blocked, not finished", #290/#292), an optional Blocked pill, and
    /// the trailing chevron.
    private func subtaskRow(_ row: SubtaskRow) -> some View {
        let task = row.task
        let done = task.workingState == WorkingState.done
        let accent = kindColor(ItemKind.task, colors)
        return HStack(spacing: 8) {
            // Leading rail+node region: the curvy spine (underlay) with the kind node landed at its column.
            ZStack(alignment: .topLeading) {
                TreeRail(
                    spine: row.spine.map { $0.boolValue },
                    depth: Int(row.depth),
                    hasChildren: row.hasChildren,
                    isExpanded: row.isExpanded,
                    color: accent
                )
                TreeNode(
                    kindColor: accent,
                    hasChildren: row.hasChildren,
                    isExpanded: row.isExpanded,
                    onToggle: {
                        if row.hasChildren {
                            component.onToggleSubtaskExpand(id: task.stableKey, currentlyExpanded: row.isExpanded)
                        }
                    }
                )
                .frame(maxHeight: .infinity)
                .offset(x: TreeGeometry.nodeCenterX(depth: Int(row.depth)) - Tree.parentDisc / 2)
            }
            .frame(width: TreeGeometry.leadingWidth(depth: Int(row.depth)))

            CheckDot(checked: done) { component.onToggleSubtaskDone(subtask: task) }
            Button { BridgeKt.openSubtask(component: component, subtask: task) } label: {
                Text(task.title)
                    .strikethrough(done)
                    .foregroundStyle((done || task.blocked) ? colors.inkMuted : colors.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.format("common_open_named_cd", task.title))
            if task.blocked {
                TreeChip(text: L.string("common_blocked"), tone: .warn)
            }
            DefernoIcon.chevronRight.image(size: 18).foregroundStyle(colors.inkMuted)
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    // MARK: - Trail: the merged, day-grouped comments + enriched-history feed (ADR-0046)

    /// The Trail (ADR-0046): the single, interleaved comments + server-history feed the component already
    /// merged newest-first — the composer inline at the top, then the feed grouped by device-local day under
    /// a TODAY-aware `DayGroupHeader`, each row a comment or an enriched, glyph-prefixed history line (tappable
    /// when it carries an old→new change diff).
    @ViewBuilder
    private func trailSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            SectionLabel("\(L.string("tasks_detail_tab_trail")) · \(value.activity.count)")
            commentComposer(isPosting: value.isPostingComment)
            if value.commentsLoading && value.activity.isEmpty {
                MutedLine(L.string("common_loading"))
            } else if value.activity.isEmpty {
                MutedLine(L.string("tasks_detail_trail_empty"))
            } else {
                // Group by the device-local day, preserving the merged newest-first order (first-seen day order).
                ForEach(groupActivity(value.activity)) { group in
                    DayGroupHeader(dayIso: group.id)
                    ForEach(group.rows) { entry in
                        trailRow(entry.item, state: value)
                    }
                }
            }
        }
    }

    /// One Trail feed row: a comment (own Edit/Delete when the current user authored it) or an enriched,
    /// glyph-prefixed history line (a `Button` opening the change diff when the row carries one).
    @ViewBuilder
    private func trailRow(_ item: ActivityItem, state value: TaskDetailState) -> some View {
        let time = TrailDateFormat.time(BridgeKt.activityItemEpochSeconds(item: item))
        if let comment = BridgeKt.activityItemComment(item: item) {
            CommentRow(
                comment: comment,
                isMine: BridgeKt.commentIsMine(state: value, comment: comment),
                time: time,
                edited: comment.editedAt != nil,
                onEdit: { component.onEditComment(commentId: $0, body: $1) },
                onDelete: { component.onDeleteComment(commentId: $0) }
            )
        } else {
            HistoryEventRow(
                item: item,
                time: time,
                onTap: BridgeKt.activityHistoryHasDiff(item: item) ? { openDiff = DiffPresentation(item: item) } : nil
            )
        }
    }

    /// Group the merged (newest-first) feed by device-local ISO day, keeping first-seen day order so a row
    /// never lands under the wrong header at a day boundary (mirrors Compose `groupBy { it.at.localDayIso() }`).
    private func groupActivity(_ activity: [ActivityItem]) -> [TrailDay] {
        var order: [String] = []
        var buckets: [String: [TrailEntry]] = [:]
        for item in activity {
            let day = BridgeKt.activityItemDayIso(item: item)
            if buckets[day] == nil {
                order.append(day)
                buckets[day] = []
            }
            buckets[day]?.append(TrailEntry(item))
        }
        return order.map { TrailDay(id: $0, rows: buckets[$0] ?? []) }
    }

    // MARK: - Comment composer + shared add field

    @ViewBuilder
    private func commentComposer(isPosting: Bool) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            TextField(L.string("tasks_detail_add_comment_placeholder"), text: $commentDraft, axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.roundedBorder)
                .focused($composerFocused)
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

    /// Trim, drop blanks, de-dup — the same normalization the Android labels row applies before forwarding.
    private func normalize(_ list: [String]) -> [String] {
        var seen = Set<String>()
        return list.map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && seen.insert($0).inserted }
    }

    // MARK: - Attachments (add + on-device playback plumbing)

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
}

// MARK: - Connected-parent amber git-graph (ADR-0044)

/// One column of the connected-parent branch (ADR-0044) — the calm git-graph thread in the Task accent. The
/// **parent** cell draws the upper node and a line descending to the row's bottom; the **current** cell
/// continues that line from the top and lands a rounded elbow in the slightly-indented lower node. Both cells
/// share the same width (26pt) so the parent + current titles stay left-aligned. dp→pt 1:1 with the Compose
/// `BranchCell` in `TaskDetailContent.kt`.
private struct BranchCell: View {
    let isCurrent: Bool
    let color: Color

    private let parentCx: CGFloat = 9
    private let currentCx: CGFloat = 18
    private let nodeR: CGFloat = 5
    private let stroke: CGFloat = 1.6
    private let elbowR: CGFloat = 7
    private let currentCy: CGFloat = 16

    var body: some View {
        Canvas { ctx, size in
            if isCurrent {
                var path = Path()
                path.move(to: CGPoint(x: parentCx, y: 0))
                path.addLine(to: CGPoint(x: parentCx, y: currentCy - elbowR))
                path.addQuadCurve(
                    to: CGPoint(x: parentCx + elbowR, y: currentCy),
                    control: CGPoint(x: parentCx, y: currentCy)
                )
                path.addLine(to: CGPoint(x: currentCx, y: currentCy))
                ctx.stroke(path, with: .color(color), lineWidth: stroke)
                ctx.fill(circle(at: CGPoint(x: currentCx, y: currentCy), r: nodeR), with: .color(color))
            } else {
                let cy = size.height / 2
                var line = Path()
                line.move(to: CGPoint(x: parentCx, y: cy))
                line.addLine(to: CGPoint(x: parentCx, y: size.height))
                ctx.stroke(line, with: .color(color), lineWidth: stroke)
                ctx.fill(circle(at: CGPoint(x: parentCx, y: cy), r: nodeR), with: .color(color))
            }
        }
        .frame(width: 26)
        .frame(maxHeight: .infinity)
    }

    private func circle(at center: CGPoint, r: CGFloat) -> Path {
        Path(ellipseIn: CGRect(x: center.x - r, y: center.y - r, width: 2 * r, height: 2 * r))
    }
}

// MARK: - Properties table row

/// The fixed left label column width — wide enough for the longest label ("ATTACHMENTS").
private let PropLabelWidth: CGFloat = 116

/// One properties-table row: a tinted mono small-caps label cell ruled off from the content cell. When
/// [onTap] is set the whole row is tappable (the STATUS row → status picker) and announced as one node via
/// [rowA11y]. Mirrors the Compose `PropertyTableRow` (a11y + geometry) in `TaskDetailSections.kt`.
private struct PropertyTableRow<Content: View>: View {
    let label: String
    var onTap: (() -> Void)?
    var rowA11y: String?
    let content: Content
    @Environment(\.defernoColors) private var colors

    init(label: String, onTap: (() -> Void)? = nil, rowA11y: String? = nil, @ViewBuilder content: () -> Content) {
        self.label = label
        self.onTap = onTap
        self.rowA11y = rowA11y
        self.content = content()
    }

    var body: some View {
        let rowBody = HStack(spacing: 0) {
            Text(label.uppercased())
                .font(.defernoMono(11, weight: .regular))
                .foregroundStyle(colors.inkMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 14)
                .frame(width: PropLabelWidth, alignment: .topLeading)
                .frame(maxHeight: .infinity, alignment: .topLeading)
                .background(colors.surfaceVariant)
            Divider().overlay { colors.outlineVariant }
            content
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
        }
        .frame(minHeight: Layout.minTouchTarget)

        if let onTap {
            Button(action: onTap) { rowBody.contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(rowA11y ?? label)
                .accessibilityAddTraits(.isButton)
        } else {
            rowBody
        }
    }
}

// MARK: - Journey status indicator (labels-above 3-node track)

/// The read-only journey STATUS indicator (ADR-0044): a labelled three-node track — initial `TO-DO` → a
/// present middle marker → terminal `DONE` — rendered from the journey reading (`journeyActiveSlot` +
/// `journeyIsShelved`/`journeyIsBlocked`) over the bridge. The active node fills in its slot colour (`DONE`
/// in success green, a blocked middle as an error-red four-point star), the not-reached nodes are a soft
/// grey, and a shelved (Dropped) reading draws a dashed tail to a hollow, struck-through `DONE`. Colour is
/// reinforcement only — each slot carries text — and the whole track is a single a11y-hidden element (the
/// STATUS row speaks the current label). Redraws the Compose `JourneyStatusIndicator`.
private struct JourneyStatusIndicator: View {
    let task: Task

    @Environment(\.defernoColors) private var colors

    private let trackHeight: CGFloat = 18
    private let nodeRadius: CGFloat = 5
    private let starRadius: CGFloat = 8
    private let trackStroke: CGFloat = 1.5
    private let dash: CGFloat = 4

    var body: some View {
        let slot = Int(BridgeKt.journeyActiveSlot(task: task))   // Initial=0, Middle=1, Terminal=2
        let token = BridgeKt.journeyLabelToken(task: task)
        let shelved = BridgeKt.journeyIsShelved(task: task)
        let blocked = BridgeKt.journeyIsBlocked(task: task)
        // When the reading is TO-DO/DONE the middle shows a muted IN-PROGRESS "not there yet" hint.
        let middleToken = (token == "TODO" || token == "DONE") ? "IN_PROGRESS" : token
        let middleColor: Color = blocked ? colors.error : (shelved ? colors.inkMuted : colors.primary)
        let idle = colors.inkMuted.opacity(0.35)

        VStack(spacing: 6) {
            // The labels overlaid above the track, each aligned over its node (start · center · end).
            ZStack {
                slotLabel(L.journeyLabel("TODO"), active: slot == 0, color: colors.primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
                slotLabel(L.journeyLabel(middleToken), active: slot == 1, color: middleColor)
                    .frame(maxWidth: .infinity, alignment: .center)
                slotLabel(L.journeyLabel("DONE"), active: slot == 2, color: colors.success, struck: shelved)
                    .frame(maxWidth: .infinity, alignment: .trailing)
            }
            // The track: a hairline through three nodes at the start / centre / end.
            Canvas { ctx, size in
                let cy = size.height / 2
                let left = CGPoint(x: nodeRadius, y: cy)
                let mid = CGPoint(x: size.width / 2, y: cy)
                let right = CGPoint(x: size.width - nodeRadius, y: cy)

                var leftLine = Path()
                leftLine.move(to: left); leftLine.addLine(to: mid)
                ctx.stroke(leftLine, with: .color(colors.outlineVariant), lineWidth: trackStroke)

                var rightLine = Path()
                rightLine.move(to: mid); rightLine.addLine(to: right)
                ctx.stroke(
                    rightLine,
                    with: .color(colors.outlineVariant),
                    style: shelved ? StrokeStyle(lineWidth: trackStroke, dash: [dash, dash]) : StrokeStyle(lineWidth: trackStroke)
                )

                fillNode(ctx, left, active: slot == 0, color: colors.primary, idle: idle)
                if blocked {
                    drawStar(ctx, mid, outer: starRadius, color: middleColor)
                } else {
                    fillNode(ctx, mid, active: slot == 1, color: middleColor, idle: idle)
                }
                if shelved {
                    // Not headed to done — a hollow ring rather than a filled node.
                    ctx.stroke(nodePath(right), with: .color(colors.inkMuted), lineWidth: trackStroke)
                } else {
                    fillNode(ctx, right, active: slot == 2, color: colors.success, idle: idle)
                }
            }
            .frame(height: trackHeight)
        }
        .accessibilityHidden(true)
    }

    private func slotLabel(_ text: String, active: Bool, color: Color, struck: Bool = false) -> some View {
        Text(text)
            .font(.defernoMono(11, weight: active ? .semibold : .regular))
            .strikethrough(struck)
            .foregroundStyle(active ? color : colors.inkMuted)
            .lineLimit(1)
    }

    private func nodePath(_ center: CGPoint) -> Path {
        Path(ellipseIn: CGRect(x: center.x - nodeRadius, y: center.y - nodeRadius, width: 2 * nodeRadius, height: 2 * nodeRadius))
    }

    private func fillNode(_ ctx: GraphicsContext, _ center: CGPoint, active: Bool, color: Color, idle: Color) {
        ctx.fill(nodePath(center), with: .color(active ? color : idle))
    }

    /// The blocked marker: a four-point star ("stuck") on the middle node, drawn in the error colour.
    private func drawStar(_ ctx: GraphicsContext, _ center: CGPoint, outer: CGFloat, color: Color) {
        let inner = outer * 0.4
        var path = Path()
        for i in 0..<8 {
            let rad = (i % 2 == 0) ? outer : inner
            let angle = (-90.0 + Double(i) * 45.0) * Double.pi / 180.0
            let point = CGPoint(
                x: center.x + CGFloat(Double(rad) * cos(angle)),
                y: center.y + CGFloat(Double(rad) * sin(angle))
            )
            if i == 0 { path.move(to: point) } else { path.addLine(to: point) }
        }
        path.closeSubpath()
        ctx.fill(path, with: .color(color))
    }
}

// MARK: - Markdown description

/// The NOTES markdown (ADR-0044): a GitHub-imported body rendered as inline GFM (not raw `**`/backticks),
/// clamped to the first lines with the rest one tap away in a sheet, selectable + copyable, links live. A
/// known platform delta from the Compose `MarkdownDescription`: `AttributedString(markdown:)` supports
/// inline syntax only (no block tables / task lists) — the "Show more" sheet still carries the full text.
private struct MarkdownDescription: View {
    let markdown: String
    let sheetTitle: String
    @State private var showingFull = false
    @Environment(\.defernoColors) private var colors

    private var attributed: AttributedString {
        (try? AttributedString(
            markdown: markdown,
            options: AttributedString.MarkdownParsingOptions(interpretedSyntax: .inlineOnlyPreservingWhitespace)
        )) ?? AttributedString(markdown)
    }

    // SwiftUI can't cheaply report whether a `lineLimit`-clamped Text truncated, so approximate "long" from
    // the source (a long body or many lines) — the sheet always shows the full text regardless.
    private var isLong: Bool {
        markdown.count > 360 || markdown.filter { $0 == "\n" }.count >= 12
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(attributed)
                .font(.body)
                .textSelection(.enabled)
                .tint(colors.primary)
                .lineLimit(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            if isLong {
                TextLink(title: L.string("common_show_more")) { showingFull = true }
            }
        }
        .sheet(isPresented: $showingFull) {
            NavigationStack {
                ScrollView {
                    Text(attributed)
                        .font(.body)
                        .textSelection(.enabled)
                        .tint(colors.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(22)
                }
                .navigationTitle(sheetTitle)
                .navigationBarTitleDisplayMode(.inline)
            }
        }
    }
}

// MARK: - Trail feed rows (comment · enriched history · day header · change diff)

/// A stable-identity wrapper for one Trail row, so `ForEach` keys on the bridge's stable id (the comment id
/// or "history:<index>") instead of a positional index — keeping a `CommentRow`'s edit `@State` bound to its
/// own comment across feed shifts (ADR-0043).
private struct TrailEntry: Identifiable {
    let id: String
    let item: ActivityItem
    init(_ item: ActivityItem) {
        self.id = BridgeKt.activityItemId(item: item)
        self.item = item
    }
}

/// One device-local day bucket of the Trail feed — a header + its rows, keyed on the ISO day.
private struct TrailDay: Identifiable {
    let id: String
    let rows: [TrailEntry]
}

/// The day-group header for the Trail (ADR-0046): a dotted rule with the raw ISO day pinned leading and an
/// uppercased localized "TODAY" centred when the day is the device-local today. The Swift twin of the Compose
/// `DayGroupHeader`/`DottedLabelDivider` — opaque `background` chips break the dots around each label.
private struct DayGroupHeader: View {
    let dayIso: String
    @Environment(\.defernoColors) private var colors

    var body: some View {
        ZStack {
            Canvas { ctx, size in
                var line = Path()
                line.move(to: CGPoint(x: 0, y: size.height / 2))
                line.addLine(to: CGPoint(x: size.width, y: size.height / 2))
                ctx.stroke(line, with: .color(colors.inkMuted), style: StrokeStyle(lineWidth: 1, dash: [2, 6]))
            }
            HStack {
                Text(dayIso)
                    .font(.defernoMono(11, weight: .semibold))
                    .foregroundStyle(colors.inkMuted)
                    .padding(.trailing, 8)
                    .background(colors.background)
                Spacer(minLength: 0)
            }
            if TrailDateFormat.dayIsoIsToday(dayIso) {
                Text(L.string("tasks_detail_due_today").uppercased())
                    .font(.defernoMono(11, weight: .semibold))
                    .foregroundStyle(colors.inkMuted)
                    .padding(.horizontal, 8)
                    .background(colors.background)
            }
        }
        .padding(.horizontal, 12)
        .padding(.top, 10)
        .padding(.bottom, 2)
        .accessibilityElement(children: .combine)
    }
}

/// A read-only server-history row (ADR-0046): a leading unicode kind glyph then the enriched, payload-rendered
/// line (the status transition, the peer-title verbs, or the humanized changed-field list) with the recorded
/// time trailing. [onTap] is non-null only when the row carries a captured old→new diff (#260): the label then
/// reads in full ink and a tap opens the [ChangeDiffSheet].
private struct HistoryEventRow: View {
    let item: ActivityItem
    let time: String
    let onTap: (() -> Void)?
    @Environment(\.defernoColors) private var colors

    var body: some View {
        let content = HStack(spacing: 8) {
            Text(BridgeKt.activityHistoryGlyph(item: item) ?? "")
                .font(.caption)
                .foregroundStyle(colors.inkMuted)
            Text(L.historyEnriched(BridgeKt.activityHistoryLine(item: item)!))
                .font(.caption)
                .foregroundStyle(onTap != nil ? colors.onSurface : colors.inkMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(time)
                .font(.caption2)
                .foregroundStyle(colors.inkMuted)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)

        if let onTap {
            Button(action: onTap) { content.contentShape(Rectangle()) }
                .buttonStyle(.plain)
        } else {
            content
        }
    }
}

/// One Trail comment (ADR-0046): 💬 + author + time (+ an edited marker), the body, and — for the current
/// user's own — inline Edit / Delete. The server enforces the real authorization; this only chooses which
/// affordances to show. Trail-styled as a calm card.
private struct CommentRow: View {
    let comment: Comment
    let isMine: Bool
    let time: String
    let edited: Bool
    let onEdit: (String, String) -> Void
    let onDelete: (String) -> Void
    @State private var editing = false
    @State private var draft = ""
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: 8) {
                Text("💬").font(.caption)
                Text(isMine ? L.string("tasks_detail_comment_author_you") : L.string("tasks_detail_comment_author_member"))
                    .font(.caption.weight(.medium))
                Text(edited ? "\(time) \(L.string("tasks_detail_comment_edited"))" : time)
                    .font(.caption2)
                    .foregroundStyle(colors.inkMuted)
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
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
    }
}

/// A stable-identity wrapper for the open change-diff sheet — `.sheet(item:)` needs Identifiable, and the
/// sealed `ActivityItem` bridges to a Swift existential without one. Keyed on the bridge's stable row id.
private struct DiffPresentation: Identifiable {
    let id: String
    let item: ActivityItem
    init(item: ActivityItem) {
        self.id = BridgeKt.activityItemId(item: item)
        self.item = item
    }
}

// MARK: - Trail date/time formatting (Swift-side — the bridge is Kotlin/Native, no java.time)

/// All human-facing Trail date/time formatting (ADR-0046): the bridge (Kotlin/Native) exposes only the ISO
/// day key + epoch seconds, so the row time, the diff subtitle, and the DEADLINE diff value are formatted here
/// with `DateFormatter` (native, locale-aware). Not file-private because `L.diffValueText` (Localization.swift)
/// calls [deadline].
enum TrailDateFormat {
    /// The row time (e.g. "4:17 PM") — the short, locale-aware time of an activity instant.
    static func time(_ epoch: Double) -> String {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.timeStyle = .short
        return formatter.string(from: Date(timeIntervalSince1970: epoch))
    }

    /// The diff subtitle / calm meta line ("MMM d · HH:mm") for an activity instant.
    static func whenLabel(_ epoch: Double) -> String {
        whenFormatter.string(from: Date(timeIntervalSince1970: epoch))
    }

    /// A DEADLINE diff value: parse the raw RFC3339 instant and render it "MMM d · HH:mm"; on a parse failure
    /// return the raw string (matches the Compose `getOrDefault(raw)`).
    static func deadline(_ rfc3339: String) -> String {
        guard let date = parseInstant(rfc3339) else { return rfc3339 }
        return whenFormatter.string(from: date)
    }

    /// The device-local ISO day (yyyy-MM-dd) for `Date()`, to compare against the bridge's `activityItemDayIso`.
    static func todayIso() -> String { isoDayFormatter.string(from: Date()) }

    /// Whether [day] (a device-local ISO day key) is the device-local today.
    static func dayIsoIsToday(_ day: String) -> Bool { day == todayIso() }

    private static func parseInstant(_ rfc3339: String) -> Date? {
        let fractional = ISO8601DateFormatter()
        fractional.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        if let date = fractional.date(from: rfc3339) { return date }
        let plain = ISO8601DateFormatter()
        plain.formatOptions = [.withInternetDateTime]
        return plain.date(from: rfc3339)
    }

    private static let whenFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.locale = .current
        formatter.dateFormat = "MMM d · HH:mm"
        return formatter
    }()

    private static let isoDayFormatter: DateFormatter = {
        let formatter = DateFormatter()
        formatter.calendar = Calendar.current
        formatter.locale = Locale(identifier: "en_US_POSIX")
        formatter.dateFormat = "yyyy-MM-dd"
        return formatter
    }()
}

// MARK: - Shared calm building blocks (kept)

private struct MutedLine: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View {
        Text(text).font(.subheadline).foregroundStyle(colors.inkMuted)
    }
}

/// The **View attachments** sheet (ADR-0044): a list with the full attachment set the compact ATTACHMENTS
/// cell summarises — each synced file (open / delete / caption) and each on-device recording (play / delete).
/// Adding a file stays on the row (its picker is the host's glue); this sheet is view + manage only.
private struct AttachmentsSheet: View {
    let attachments: [Attachment]
    let onDeviceAttachments: [OnDeviceAttachment]
    @ObservedObject var player: OnDeviceAudioPlayer
    let onDelete: (String) -> Void
    let onSetCaption: (String, String) -> Void
    let onPlayToggle: (OnDeviceAttachment) -> Void
    let onDeleteOnDevice: (String) -> Void

    var body: some View {
        NavigationStack {
            List {
                ForEach(attachments, id: \.id) { attachment in
                    AttachmentRow(
                        attachment: attachment,
                        onDelete: onDelete,
                        onSetCaption: onSetCaption
                    )
                }
                ForEach(onDeviceAttachments, id: \.id) { attachment in
                    OnDeviceAttachmentRow(
                        attachment: attachment,
                        player: player,
                        onPlayToggle: onPlayToggle,
                        onDelete: onDeleteOnDevice
                    )
                }
            }
            .navigationTitle(L.string("tasks_detail_section_attachments"))
            .navigationBarTitleDisplayMode(.inline)
        }
    }
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
                    .accessibilityLabel(L.format("tasks_detail_play_attachment_a11y", attachment.filename))
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

/// The four actions on the Task detail's add sheet (ADR-0044 parity with Android's FAB add sheet). File-scoped
/// so both `TaskDetailView` (which fires the choice) and `AddActionsSheet` (which offers it) share the type.
private enum AddAction { case subtask, comment, plan, status }

/// The Task detail's **add sheet** (ADR-0044 parity with Android's FAB + ModalBottomSheet): the task's top
/// actions as large, full-width tappable rows — Add subtask · Add comment · Add to today's plan · Change
/// status — the bottom-sheet twin of the Compose `AddActionRow` list (no icons, body-rank rows). [select]
/// records the choice and the presenter dismisses + fires it in the sheet's onDismiss (no sheet-over-sheet).
/// The rows live in a `ScrollView` with `[.height(260), .large]` detents + a drag indicator: compact at
/// default type, but at large Dynamic Type / long locales the rows grow + wrap so the content scrolls (and the
/// sheet can be dragged to `.large`) — no row is ever clipped. A fixed-height detent alone would NOT scroll,
/// so a grown 4th row (`Change status`) would fall off-screen; Android's `ModalBottomSheet` wrap-sizes instead.
private struct AddActionsSheet: View {
    let select: (AddAction) -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 4) {
                row(L.string("tasks_menu_add_subtask"), .subtask)
                row(L.string("tasks_menu_add_comment"), .comment)
                row(L.string("tasks_menu_add_to_plan"), .plan)
                row(L.string("tasks_menu_change_status"), .status)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 22)
            .padding(.top, 20)
            .padding(.bottom, 12)
        }
        .presentationDetents([.height(260), .large])
        .presentationDragIndicator(.visible)
    }

    /// One add-sheet row: a full-width, MinTouchTarget-tall tappable label — no icon, matching the Compose
    /// `AddActionRow` (a `bodyLarge` row). Tapping records the choice via [select].
    private func row(_ label: String, _ action: AddAction) -> some View {
        Button { select(action) } label: {
            Text(label)
                .font(.body)
                .foregroundStyle(colors.onSurface)
                .frame(maxWidth: .infinity, minHeight: Layout.minTouchTarget, alignment: .leading)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// A calm, auto-dismissing confirmation toast — the iOS twin of Android's `Toast`. A pill that fades/slides in,
/// holds briefly, then hides itself; styled like the app's `UndoSnackbar` (surface card, hairline, soft
/// shadow). [token] drives it: each bump re-arms the hold and re-shows (so a repeat action retriggers it), and
/// the initial value never shows unbidden. VoiceOver is served by an explicit announcement at the call site, so
/// the transient visual is a11y-hidden here (it would otherwise steal focus without being reliably read).
private struct ConfirmationToast: View {
    let message: String
    let token: Int
    @Environment(\.defernoColors) private var colors
    @State private var shown = false

    var body: some View {
        Group {
            if shown {
                Text(message)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(colors.onSurface)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 18)
                    .padding(.vertical, 10)
                    .background(colors.surfaceCard, in: Capsule())
                    .overlay(Capsule().strokeBorder(colors.outlineVariant, lineWidth: 1))
                    .shadow(color: .black.opacity(0.10), radius: 8, y: 2)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        // Re-arm on each new bump (a fresh add); onChange skips the initial value so it never shows unbidden.
        .onChange(of: token) { _ in arm() }
        .accessibilityHidden(true)
    }

    private func arm() {
        withAnimation(.easeOut(duration: 0.2)) { shown = true }
        let key = token
        DispatchQueue.main.asyncAfter(deadline: .now() + 2.4) {
            // Only hide if no newer bump re-armed us (key unchanged) — a later toast owns its own dismissal.
            if key == token { withAnimation(.easeIn(duration: 0.25)) { shown = false } }
        }
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

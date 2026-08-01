import AVFoundation
import Deferno
import SwiftUI
import UniformTypeIdentifiers

/// The Task detail pane (#195, reshaped by ADR-0044, re-merged by ADR-0046). Thin renderer of
/// `TaskDetailComponent`: observes the hydrating row and forwards the close / add-to-plan / status intents.
/// The single heading is a **connected-parent header** (the immediate parent, tappable → pushes its detail)
/// and the body is two tabs — **Info** (NOTES → Add-to-plan → PROPERTIES → SUBTASKS) · **Trail**, the one
/// interleaved newest-first feed of comments + enriched read-only history (ADR-0046 collapsed ADR-0044's
/// Comments/History split; the enriched rows and the change-diff sheet are the iOS twin). STATUS is a
/// read-only journey indicator that opens a status-picker sheet on tap (the inline working-state chips are
/// gone). The component hydrates on creation (summary → full, #22); this View just reflects its state, and
/// is shared with the inline pane and the detached window (#196).
///
/// **ATTACHMENTS are live here since #368 G11.** The *synced* half — add (`.fileImporter` → the upload
/// seam) / view / caption / delete — is the real gap that closed. The *on-device* half (brain-dump
/// recordings + their player) was ported alongside it but sat empty on macOS, since nothing here recorded a
/// brain dump. **#368 Tranche 5 gave the Mac a recorder**, so retained recordings now genuinely appear —
/// the manage sheet was deliberately carried as one shared shape rather than a macOS fork, and that bet paid.
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
    /// Whether this detail is hosted somewhere that can actually present a shell overlay. False only in the
    /// detached `task-detail` window (#196): its `TaskDetailStackComponent` drops
    /// `Output.BreakdownRequested` on the floor, and the window hosts no overlay slot of its own — so
    /// "Break this down" there would be a menu item that does nothing. Gate it rather than ship a dead
    /// route; the item is live in the main shell, which is where the Breakdown overlay lives (#368 G10).
    var hostsOverlays: Bool = true
    @StateObject private var state: StateFlowObserver<TaskDetailState>
    @State private var newLabel = ""
    @State private var newSubtask = ""
    @State private var commentDraft = ""
    /// The active body tab (ADR-0046: Info · Trail, Info default — the Comments/History split of ADR-0044
    /// is gone). The View is re-created per Task via `.id(detailKey)`, so this resets when the pane re-keys.
    @State private var tab: DetailTab = .info
    /// Whether the status-picker sheet is up (opened by tapping the STATUS row — the inline chips are gone).
    @State private var showingStatusPicker = false
    /// Whether the overflow's Delete confirmation is up.
    @State private var showingDeleteConfirm = false
    /// The Trail row whose old→new change diff is open in a sheet, or nil (ADR-0046 / #260).
    @State private var openDiff: DiffPresentation?
    @FocusState private var subtaskFieldFocused: Bool
    /// ATTACHMENTS (#368 G11): the file picker, and the manage sheet the row's "View" opens.
    @State private var importing = false
    @State private var showingAttachmentsSheet = false
    /// Plays an on-device brain-dump recording (#272) from the bytes the bridge hands back — no network,
    /// no signed URL. Held here so it isn't deallocated mid-clip. Live on macOS since #368 Tranche 5.
    @StateObject private var audioPlayer = OnDeviceAudioPlayer()
    /// The transient confirmation toast (the macOS twin of the iOS `ConfirmationToast` / Android's Toast):
    /// [toastMessage] is the last message and bumping [toastToken] re-arms + re-shows it. See `showToast(_:)`.
    @State private var toastMessage = ""
    @State private var toastToken = 0
    @Environment(\.defernoColors) private var colors
    @Environment(\.openURL) private var openURL

    enum DetailTab { case info, trail }

    init(
        component: TaskDetailComponent,
        hidesBackControl: Bool = false,
        showsHeader: Bool = true,
        hostsOverlays: Bool = true
    ) {
        self.component = component
        self.hidesBackControl = hidesBackControl
        self.showsHeader = showsHeader
        self.hostsOverlays = hostsOverlays
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
        .background(colors.background)
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
                    Text(L.string("tasks_detail_tab_trail")).tag(DetailTab.trail)
                }
                .pickerStyle(.segmented)
                .labelsHidden()

                switch tab {
                case .info: infoTab(task, value)
                case .trail: trailSection(value)
                }
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
        // The confirmation toast (#368 G24e): rides at the foot of the body when add-to-plan fires, then
        // auto-dismisses. macOS has no FAB, so there is none of iOS's 96pt clearance to reserve. It is
        // click-through — unlike a phone, a pointer is likely to be over the body the whole 2.4s it holds,
        // and a transient pill that eats clicks would read as the pane having frozen.
        .overlay(alignment: .bottom) {
            ConfirmationToast(message: toastMessage, token: toastToken)
                .padding(.horizontal, Layout.gutter)
                .padding(.bottom, 16)
                .allowsHitTesting(false)
        }
        .sheet(isPresented: $showingStatusPicker) {
            StatusPickerSheet(current: task.workingState) {
                component.onSetWorkingState(target: $0)
                showingStatusPicker = false
            }
        }
        // The tapped Trail row's old→new change diff (#260). `.sheet(item:)` — unlike the five other
        // macosApp sheets — because the content derives wholly from the tapped row. No "Open item" action:
        // the viewer is already inside this item. No reset on re-key: `.id(detailKey)` rebuilds the View.
        .sheet(item: $openDiff) { presentation in
            let line = BridgeKt.activityHistoryLine(item: presentation.item)
            ChangeDiffSheet(
                title: line.map { L.historyEnriched($0) } ?? "",
                subtitle: TrailDateFormat.whenLabel(BridgeKt.activityItemEpochSeconds(item: presentation.item)),
                rows: BridgeKt.activityHistoryDiffRows(item: presentation.item)
            )
        }
        // "View" on the ATTACHMENTS row opens the full manage list (open / caption / delete, plus playback
        // for an on-device recording). The picked-file path stays on the row — this sheet is manage-only.
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
        // `.fileImporter`, not `NSOpenPanel`: it is the idiom this target already ships (FeedbackView) and
        // it needs no AppKit import. The app is unsandboxed, so the security-scoped bracket in
        // `addAttachments` is a no-op today — kept because it is what makes the read correct if the App
        // Sandbox is ever switched on.
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { addAttachments(urls) }
        }
        .confirmationDialog(
            L.string("tasks_detail_delete_confirm_title"),
            isPresented: $showingDeleteConfirm,
            titleVisibility: .visible
        ) {
            Button(L.string("common_delete"), role: .destructive) { component.onDelete() }
            Button(L.string("common_cancel"), role: .cancel) {}
        } message: {
            // The stakes line every other Deferno delete confirm carries (the tree row's already did) —
            // a destructive confirm that only asks "Delete?" doesn't say what is being spent (#368 G24b).
            Text(L.string("common_cannot_be_undone"))
        }
        // ADR-0044: the drilled-overflow "Add subtask" reveals the inline composer. macOS has no compact
        // shell bar, so the overflow lives in the header; reacting here focuses the add-subtask field.
        // TODO(port-verify): confirm the focus lands and the Info tab is showing after an Xcode build.
        .onChange(of: value.revealAddSubtaskComposer) { _, token in
            if token > 0 { tab = .info; subtaskFieldFocused = true }
        }
    }

    // MARK: - Tabs

    @ViewBuilder
    private func infoTab(_ task: Task, _ value: TaskDetailState) -> some View {
        // NOTES (#368 G14): an eyebrow over the description rendered as **markdown** — a GitHub-imported
        // body is GFM, and the raw `**bold**` this used to print was the tracker's markup leaking through.
        // Clamped, selectable (copying prose out of a task matters more on a desktop than anywhere else),
        // with the full text one click away. The header hides only during the brief pre-hydration gap, so
        // it still appears for an empty description once hydration settles.
        let hasDescription = (task.itemDescription?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false)
        if hasDescription || !value.isHydrating {
            SectionLabel(L.string("new_notes_label"))
        }
        if hasDescription, let description = task.itemDescription {
            MarkdownDescription(markdown: description, sheetTitle: L.string("new_notes_label"))
        } else if !value.isHydrating {
            Text(L.string("tasks_detail_no_description"))
                .font(.callout)
                .foregroundStyle(colors.inkMuted)
        }

        // Add-to-plan confirms itself (#368 G24e): today's plan isn't on this screen, so without the toast
        // the click had no visible result at all and read as a no-op.
        Button {
            component.onAddToPlanClicked()
            showToast(L.string("breakdown_msg_added_to_plan"))
        } label: {
            Text(L.string("tasks_menu_add_to_plan")).frame(maxWidth: .infinity)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .frame(minHeight: Layout.minTouchTarget)

        Divider()
        propertiesSection(task, value)
        Divider()
        subtasksSection(value)
    }

    /// Show a transient confirmation toast with [message] + announce it for VoiceOver. Setting the message
    /// and bumping the token together re-arm + re-show the single toast host.
    ///
    /// The announcement is SwiftUI's own `AccessibilityNotification` rather than the iOS twin's
    /// `UIAccessibility.post` (UIKit-only): its AppKit equivalent would mean importing AppKit here, and no
    /// file under `macosApp/` does.
    private func showToast(_ message: String) {
        toastMessage = message
        toastToken += 1
        AccessibilityNotification.Announcement(message).post()
    }

    /// The **Trail** (ADR-0046) — the one interleaved feed that replaced the ADR-0044 Comments/History tab
    /// split: comments and enriched read-only server history merged newest-first (the component's own sort —
    /// never re-sort or filter here), the composer inline at the top, then the feed grouped by device-local
    /// day under a TODAY-aware `DayGroupHeader`. Each row is a comment or an enriched, glyph-prefixed history
    /// line (clickable when it carries an old→new change diff).
    @ViewBuilder
    private func trailSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle(L.string("tasks_detail_tab_trail"), trailing: "\(value.activity.count)")
            commentComposer(isPosting: value.isPostingComment)
            if value.commentsLoading && value.activity.isEmpty {
                MutedLine(L.string("common_loading"))
            } else if value.activity.isEmpty {
                // An empty Trail is a valid terminal state — no error branch (ADR-0043).
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
    /// glyph-prefixed history line (clickable — opening the change diff — when the row carries one).
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

    // MARK: - Overflow (Add subtask · Delete)

    /// The self-contained detail overflow (ADR-0044) — replaces the old always-visible chips' "Set aside"
    /// (Dropped now reaches only via the status picker). Break this down opens the on-device impediment
    /// flow; Add subtask bumps the reveal token; Delete confirms.
    ///
    /// **"Break this down" was hidden here through #368 Tranche 4** and is restored now. The intent was
    /// always sound — `onBreakdownClicked()` really does push `OverlayRoute.Breakdown` on the shared shell —
    /// but macOS had no Breakdown surface, and `MainShellView.overlayPresented` derived only from Search /
    /// New / Feedback, so the route silently occupied the shell's single overlay slot while presenting
    /// nothing, and the next `onBack()` spent itself dismissing that invisible overlay. On the Tasks path
    /// the chrome is non-drilled (there is no ← at all), so the stale slot was unrecoverable through the UI.
    /// Tranche 5 landed both halves — `Breakdown/BreakdownView.swift` and the `overlayBreakdown` branch in
    /// `MainShellView` — so the item is a live route again. Do not re-add one without the other.
    private var overflowMenu: some View {
        Menu {
            if hostsOverlays {
                Button { component.onBreakdownClicked() } label: {
                    Label(L.string("tasks_menu_break_this_down"), systemImage: "square.split.2x2")
                }
            }
            Button { component.onAddSubtaskRequested() } label: {
                Label(L.string("tasks_menu_add_subtask"), systemImage: "plus")
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

    // MARK: - Properties (Status · When · Labels · Owner · Source · Attachments)

    /// The properties table. Only the rows an item actually carries appear: OWNER is a shared/multi-group
    /// concern (one org means every item has the same owner and the row is noise), SOURCE exists only for
    /// an imported item. WHEN and ATTACHMENTS are unconditional — each holds its own add affordance.
    ///
    /// There is deliberately **no TIME row** any more: the WHEN picker below owns the clock, so a separate
    /// read-only "11:59 PM" line duplicated it and (worse) printed the all-day end-of-day sentinel as if it
    /// were a real deadline time. iOS's table never had one.
    @ViewBuilder
    private func propertiesSection(_ task: Task, _ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            SectionTitle(L.string("tasks_detail_section_properties"))
            statusRow(task)
            dueRow(task)
            labelsRow(task)
            let ownerLabel = BridgeKt.taskOwnerLabel(task: task)
            if value.ownerGroupCount > 1 && ownerLabel != "—" {
                propertyRow(label: L.string("tasks_detail_property_owner"), value: ownerLabel)
            }
            if let providerToken = BridgeKt.taskSourceProviderToken(task: task) {
                sourceRow(task, providerToken: providerToken)
            }
            attachmentsRow(value)
        }
    }

    /// The STATUS row (ADR-0044): a read-only journey indicator that, when tapped, opens the status picker
    /// sheet — the only path to Dropped ("NOT DOING") now. The spoken label carries the current journey label.
    @ViewBuilder
    private func statusRow(_ task: Task) -> some View {
        Button { showingStatusPicker = true } label: {
            HStack {
                Text(L.string("tasks_detail_property_status"))
                    .font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
                JourneyStatusIndicator(task: task)
                Spacer()
                Image(systemName: "chevron.right").font(.caption).foregroundStyle(colors.inkMuted)
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
            Text(label).font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
            Text(value).font(.body).foregroundStyle(value == "—" ? colors.inkMuted : colors.onSurface)
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The SOURCE row (#368 G13): the provider mark + the origin label (`owner/repo#N` when present, else
    /// the provider name), underlined and click-through when the provenance carries a URL. Read-only —
    /// this is provenance, not an editor. Rendered only for an imported item (`providerToken != nil`).
    ///
    /// The four bridge seams behind this row have existed on macOS since the first port with **no** Swift
    /// caller, so an imported item showed nothing at all about where it came from — the tree row's
    /// `SourceMark` was the only cue, and the detail pane dropped it.
    @ViewBuilder
    private func sourceRow(_ task: Task, providerToken: String) -> some View {
        let origin = BridgeKt.taskSourceOriginLabel(task: task) ?? ""
        let source: ItemSource = providerToken == "GITHUB" ? ItemSource.gitHub : ItemSource.googleCalendar
        HStack {
            Text(L.string("tasks_detail_property_source"))
                .font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
            SourceMark(source: source)
            if let urlString = BridgeKt.taskSourceUrl(task: task), let url = URL(string: urlString) {
                Button { openURL(url) } label: {
                    Text(origin).underline().foregroundStyle(colors.onSurface)
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.format("common_open_named_cd", origin))
                // The desktop tooltip a link-shaped click surface earns here (the `SearchBarDisplay` rule):
                // `owner/repo#1234` truncates in a narrow pane and the pointer is the way to read it whole.
                .help(origin)
            } else {
                Text(origin).foregroundStyle(colors.onSurface)
            }
            Spacer(minLength: 0)
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The ATTACHMENTS row (#368 G11): a compact count + combined-size summary over **View** (opens the
    /// manage sheet) and **Add file** (the picker). Unconditional — it carries the add affordance even at
    /// zero files. Keeping the list off the row lets attachments sit in the same narrow value column as
    /// every other property, which is what makes them survive the 250pt inline-pane minimum (#194).
    ///
    /// The count spans both halves, but only the synced half is ever non-empty on macOS today (see the
    /// type doc): `onDeviceAttachments` needs a brain-dump recorder this app doesn't have yet.
    @ViewBuilder
    private func attachmentsRow(_ value: TaskDetailState) -> some View {
        let count = value.attachments.count + value.onDeviceAttachments.count
        HStack(alignment: .top) {
            Text(L.string("tasks_detail_section_attachments"))
                .font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
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
            Spacer(minLength: 0)
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// A friendly file size — the native `ByteCountFormatter`, the Apple twin of Android's `formatBytes`.
    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }

    /// The editable WHEN row: a native `DatePicker` when a deadline is set (with a Clear button), else a
    /// "Set" button that seeds today. It owns **both** deadline axes since #368 G12 — the day and, when the
    /// deadline is timed, the clock.
    ///
    /// **A deadline is either all-day or timed, and the row shows which.** Timed → a combined
    /// `[.date, .hourAndMinute]` field plus "Clear deadline time" (back to all-day). All-day → a date-only
    /// field plus "Add deadline time" (which seeds the shared 9:00 default). Rendering the clock
    /// unconditionally would state a time an all-day task does not have, and one stray edit of that phantom
    /// field would silently convert the task.
    ///
    /// Both branches seed from `taskDeadlinePickerEpochSeconds` and write through `applyDeadlinePicker` —
    /// never from raw `completeBy`, whose clock is the 23:59:59 end-of-day sentinel for an all-day task;
    /// feeding that to a picker would read every all-day deadline as "11:59 PM". `applyDeadlinePicker`
    /// also dispatches only the axis that actually moved, so nudging the day never rewrites the clock (and
    /// it reads the current values live off the component, so a fast sequence of edits stays correct).
    ///
    /// The no-deadline branch seeds through the raw `setTaskDeadline` — the **date** axis only, deliberately.
    /// A first deadline should be all-day (the three-state row then offers "Add deadline time"), and routing
    /// it through `applyDeadlinePicker` instead would compare now's wall clock against the 9:00 seed, find
    /// them different, and silently make the Task *timed* at whatever minute the button was pressed. iOS grew
    /// the same branch: it used to drop the WHEN row entirely when undated, leaving no way to give a Task a
    /// first deadline from detail.
    @ViewBuilder
    private func dueRow(_ task: Task) -> some View {
        let hasDue = BridgeKt.taskDeadlinePickerEpochSeconds(task: task) >= 0
        let hasTime = BridgeKt.taskDeadlineHasTime(task: task)
        let axes: DatePickerComponents = hasTime ? [.date, .hourAndMinute] : [.date]
        // ADR-0044: labelled WHEN (was DUE) and, when a deadline is set, followed by the relative-day
        // reading ("In 3 days" / "Yesterday") mapped from the typed bridge token.
        HStack {
            if hasDue {
                DatePicker(
                    L.string("tasks_detail_property_when"),
                    selection: Binding(
                        get: { Date(timeIntervalSince1970: BridgeKt.taskDeadlinePickerEpochSeconds(task: task)) },
                        set: { BridgeKt.applyDeadlinePicker(component: component, epochSeconds: $0.timeIntervalSince1970) }
                    ),
                    displayedComponents: axes
                )
                // The desktop stepper field, not iOS's chip + graphical-calendar popover — that whole
                // construction exists to fight iPhone popover-to-sheet adaptation, and a calendar grid is
                // the wrong idiom next to a keyboard.
                .datePickerStyle(.field)
                // The spoken label tracks what the field actually edits, so it never promises a clock the
                // all-day variant doesn't show.
                .accessibilityLabel(hasTime ? L.string("new_deadline_time_cd") : L.string("tasks_detail_property_when"))
                if let token = BridgeKt.taskDueRelativeToken(task: task) {
                    Text(L.relativeDay(token, Int(BridgeKt.taskDueRelativeCount(task: task))))
                        .font(.footnote).foregroundStyle(colors.inkMuted)
                }
                // Two distinct clears sit side by side, as on iOS: this one drops only the clock (the day
                // survives, the task goes back to all-day); the one below drops the deadline entirely.
                if hasTime {
                    Button(L.string("new_deadline_time_clear_a11y")) { BridgeKt.clearTaskDeadlineTime(component: component) }
                        .font(.subheadline)
                } else {
                    Button(L.string("new_deadline_time_add_a11y")) { BridgeKt.addTaskDeadlineTime(component: component) }
                        .font(.subheadline)
                }
                Button(L.string("common_clear")) { BridgeKt.clearTaskDeadline(component: component) }
                    .font(.subheadline)
                    .accessibilityLabel(L.string("tasks_detail_clear_due_date_a11y"))
            } else {
                Text(L.string("tasks_detail_property_when")).font(.subheadline).foregroundStyle(colors.inkMuted).frame(width: 72, alignment: .leading)
                Text("—").foregroundStyle(colors.inkMuted)
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
            SectionLabel(L.string("common_labels"))
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

    // MARK: - Attachments (add + on-device playback plumbing)

    /// Play/pause an on-device recording (#272). Same row already loaded → toggle without re-reading bytes;
    /// a different row → fetch its bytes through the bridge and start it (which stops any current clip).
    private func togglePlay(_ attachment: OnDeviceAttachment) {
        if audioPlayer.activeId == attachment.id {
            audioPlayer.toggle()
        } else {
            BridgeKt.onDeviceAttachmentData(component: component, attachmentId: attachment.id) { data in
                if let data = data { audioPlayer.play(data as Data, id: attachment.id) }
            }
        }
    }

    // The web's attachment limits: at most 5 files per add, 25 MB each. These are product rules the server
    // also enforces — dropping them here would just turn a rejected upload into a silent failure.
    private static let maxAttachments = 5
    private static let maxAttachmentBytes = 25 * 1024 * 1024

    /// Read each picked file's bytes and hand them to the component as `NSData` (the Apple twin of Android's
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
                            .font(.footnote).foregroundStyle(colors.inkMuted)
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
                Text(L.string("tasks_detail_no_subtasks_body")).font(.subheadline).foregroundStyle(colors.inkMuted)
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
            // The desktop send idiom — the field itself takes plain Return for a newline.
            .keyboardShortcut(.return, modifiers: .command)
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
                            Text(ref).font(.footnote.monospaced()).foregroundStyle(colors.inkMuted)
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
                    Text(titleWithExternalRef)
                        .font(.title2.weight(.semibold))
                        .accessibilityAddTraits(.isHeader)
                    if let ref = task.ref {
                        Text(ref).font(.footnote.monospaced()).foregroundStyle(colors.inkMuted)
                    }
                }
                Spacer(minLength: 0)
                overflow()
            }
        }
    }

    /// The Task title with its dimmed `[GitHub#42]` external-ref prefix (ADR-0044, #368 G13). The tracker
    /// owns the title of an imported item, so the prefix is derived client-side from the provenance rather
    /// than being part of the stored title — and it is drawn muted so it reads as a tag, not as words the
    /// user wrote. A native Deferno item has no prefix and this is just the title.
    private var titleWithExternalRef: AttributedString {
        var title = AttributedString(task.title)
        title.foregroundColor = colors.onSurface
        guard let prefix = BridgeKt.taskExternalRefPrefix(task: task) else { return title }
        var prefixAttr = AttributedString(prefix + " ")
        prefixAttr.foregroundColor = colors.inkMuted
        return prefixAttr + title
    }
}

/// The title-less drilled Back bar (ADR-0044) — the connected-parent node is the heading, so this carries
/// only the Back affordance (`nil` at a detached window's root, #196). Replaces the old titled `PaneHeader`.
private struct DrilledBackBar: View {
    let onBack: (() -> Void)?
    @Environment(\.defernoColors) private var colors

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
        // `surface`, a shade off the pane's `background` — the same lift `PaneHeader` gives every other bar.
        .background(colors.surface)
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
        let fg: Color = tone == .blocked ? colors.error : (on ? colors.onPrimary : (muted ? colors.inkMuted : colors.onSurfaceVariant))
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

/// One row of the subtask outline, drawn with the same connected-tree **filigree** as the Tasks tree
/// (#368 G24d): the curvy `TreeRail` + the kind node (a fold disc for a parent, a kind dot for a leaf), then
/// the round `CheckDot`, a drill-in title, an optional Blocked chip, and the trailing chevron. The outline is
/// pre-flattened by the component with the same fold mechanism as the Tasks tree (ADR-0049), so a fold toggle
/// here persists to the shared device-local store — it re-flattens the Tasks tree too and survives restart.
///
/// This replaced a flat `depth * 18` indent with a bare chevron. Nesting was inferable only by eyeballing
/// left edges: two siblings under different parents at the same depth looked like one flat list, and the
/// rail is what makes the shape readable. The fold intent moved onto `TreeNode` (the disc *is* the control).
///
/// There is deliberately **no `WorkingStateBadge`** here, matching iOS: the strike-through and the filled
/// `CheckDot` already carry done-ness, and a row that must fit rail + node + dot + title + chip + chevron
/// inside a 250pt pane (#194) can't spend width on a second reading of the same fact.
private struct SubtaskOutlineRow: View {
    let row: SubtaskRow
    let onToggleExpand: (String, Bool) -> Void
    let onToggleDone: (Task) -> Void
    let onOpen: (Task) -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        let task = row.task
        let done = task.workingState == WorkingState.done
        // A subtask outline is Tasks-only, so the accent is the Task kind colour for every row.
        let accent = kindColor(ItemKind.task, colors)
        HStack(spacing: 8) {
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
                    onToggle: { if row.hasChildren { onToggleExpand(task.stableKey, row.isExpanded) } }
                )
                .frame(maxHeight: .infinity)
                .offset(x: TreeGeometry.nodeCenterX(depth: Int(row.depth)) - Tree.parentDisc / 2)
            }
            .frame(width: TreeGeometry.leadingWidth(depth: Int(row.depth)))

            CheckDot(checked: done) { onToggleDone(task) }
            Button { onOpen(task) } label: {
                Text(task.title)
                    // Blocked mutes (but doesn't strike) like the tree row — "blocked, not finished" (#290/#292).
                    .strikethrough(done)
                    .foregroundStyle((done || task.blocked) ? colors.inkMuted : colors.onSurface)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(L.format("common_open_named_cd", task.title))
            if task.blocked {
                // `.warn`, not `.neutral`: a blocked child is the one thing on this row that wants the eye,
                // and the muted grey pill it used to render was indistinguishable from ordinary metadata.
                DependencyBadge(text: L.string("common_blocked"), tone: .warn, semanticLabel: L.string("common_blocked"))
            }
            DefernoIcon.chevronRight.image(size: 18).foregroundStyle(colors.inkMuted)
        }
        .frame(minHeight: Layout.minTouchTarget)
    }
}

/// A calm section header: a heading title with an optional trailing count (e.g. "2/5").
private struct SectionTitle: View {
    let title: String
    var trailing: String?
    @Environment(\.defernoColors) private var colors
    init(_ title: String, trailing: String? = nil) { self.title = title; self.trailing = trailing }
    var body: some View {
        HStack {
            Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(colors.onSurfaceVariant)
                .accessibilityAddTraits(.isHeader)
            Spacer()
            if let trailing { Text(trailing).font(.footnote).foregroundStyle(colors.inkMuted) }
        }
    }
}

/// A single muted line — the empty / loading placeholder inside a section.
private struct MutedLine: View {
    let text: String
    @Environment(\.defernoColors) private var colors
    init(_ text: String) { self.text = text }
    var body: some View { Text(text).font(.subheadline).foregroundStyle(colors.inkMuted) }
}

// MARK: - Trail feed rows (comment · enriched history · day header · change diff)

/// One Trail comment (ADR-0046): 💬 + author + time (+ an edited marker), the body (or the encrypted
/// placeholder), and — for the current user's own — inline Edit / Delete. The server enforces the real
/// authorization; `isMine` (from the bridge) only chooses which affordances to show. Editing state is local
/// so each row toggles alone. Trail-styled as a calm card.
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
                Text("💬").font(.caption).accessibilityHidden(true)
                Text(isMine ? L.string("tasks_detail_comment_author_you") : L.string("tasks_detail_comment_author_member")).font(.caption.weight(.medium))
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
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(colors.surfaceCard, in: RoundedRectangle(cornerRadius: 10, style: .continuous))
    }
}

/// A stable-identity wrapper for one Trail row. The sealed `ActivityItem` bridges to a Swift protocol
/// (an existential with no id-KeyPath), so `ForEach` keys on the bridge's stable id — the comment id or
/// "history:<index>" — instead of a positional index, keeping a CommentRow's edit `@State` bound to its
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
///
/// The chips paint `colors.background` because that is what the pane paints (`TaskDetailView.body`); the two
/// have to stay the same colour or the labels read as floating rectangles sitting on the rule.
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
        .padding(.horizontal, Layout.gutter)
        .padding(.top, 8)
        .padding(.bottom, 2)
        .accessibilityElement(children: .combine)
    }
}

/// A read-only server-history row (ADR-0046): a leading unicode kind glyph then the enriched, payload-rendered
/// line (the status transition, the peer-title verbs, or the humanized changed-field list) with the recorded
/// time trailing. [onTap] is non-nil only when the row carries a captured old→new diff (#260): the label then
/// reads in full ink, the row highlights on hover (ink alone is an invisible affordance to a pointer), and a
/// click opens the [ChangeDiffSheet].
private struct HistoryEventRow: View {
    let item: ActivityItem
    let time: String
    let onTap: (() -> Void)?
    @State private var hovering = false
    @Environment(\.defernoColors) private var colors

    var body: some View {
        // Safe: `trailRow` only builds this branch once `activityItemComment` proved nil, and the bridge
        // returns a line for every non-comment row.
        let content = HStack(spacing: 8) {
            Text(BridgeKt.activityHistoryGlyph(item: item) ?? "")
                .font(.caption)
                .foregroundStyle(colors.inkMuted)
                .accessibilityHidden(true)
            Text(L.historyEnriched(BridgeKt.activityHistoryLine(item: item)!))
                .font(.caption)
                .foregroundStyle(onTap != nil ? colors.onSurface : colors.inkMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(time)
                .font(.caption2)
                .foregroundStyle(colors.inkMuted)
        }
        .padding(.horizontal, Layout.gutter)
        .padding(.vertical, 4)
        .background(
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(onTap != nil && hovering ? colors.surfaceVariant : .clear)
        )
        .accessibilityElement(children: .combine)

        if let onTap {
            Button(action: onTap) { content.contentShape(Rectangle()) }
                .buttonStyle(.plain)
                .onHover { hovering = $0 }
        } else {
            content
        }
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

// MARK: - NOTES markdown (#368 G14)

/// The NOTES markdown (ADR-0044) — the macOS twin of the iOS `MarkdownDescription`, which likewise keeps it
/// private to the detail screen. A GitHub-imported body is GFM, so it renders as inline markdown rather than
/// printing raw `**`/backticks; it is clamped so a 900-line spec doesn't own the pane, selectable + copyable
/// (the point of a desktop client), links live, and the full text is one click away.
///
/// A known platform delta from the Compose `MarkdownDescription`: `AttributedString(markdown:)` handles
/// inline syntax only — no block tables or task lists. The "Show more" sheet still carries the full text.
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
                // Explicit ink, unlike the iOS twin: without it the text falls through to the AppKit label
                // colour and stops tracking the active palette.
                .foregroundStyle(colors.onSurface)
                .textSelection(.enabled)
                .tint(colors.primary)
                .lineLimit(12)
                .frame(maxWidth: .infinity, alignment: .leading)
            if isLong {
                TextLink(title: L.string("common_show_more")) { showingFull = true }
            }
        }
        .sheet(isPresented: $showingFull) { MarkdownFullSheet(title: sheetTitle, text: attributed) }
    }
}

/// The full-NOTES sheet. iOS gets its title and its way out from a `NavigationStack` + `.navigationTitle`;
/// both compile on macOS and do nothing, which would leave an unbounded modal with no dismiss control. So
/// this is framed like `ChangeDiffSheet`: an explicit heading, an explicit size, and a real Done button
/// doubling as the `.cancelAction` so Escape works. It also paints its own background — a macOS sheet does
/// not inherit the presenting pane's.
private struct MarkdownFullSheet: View {
    let title: String
    let text: AttributedString
    @Environment(\.dismiss) private var dismiss
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    Text(title)
                        .font(.title3)
                        .accessibilityAddTraits(.isHeader)
                    Text(text)
                        .font(.body)
                        .foregroundStyle(colors.onSurface)
                        .textSelection(.enabled)
                        .tint(colors.primary)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            Divider()
            HStack {
                Spacer()
                // `common_done` ("Fertig"/"Listo"), NOT `calendar_action_done` — the latter is the imperative
                // "complete the task", which on a dismiss button would read as marking the item done.
                Button(L.string("common_done")) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .frame(minWidth: 420, idealWidth: 460, minHeight: 300, idealHeight: 440)
        .background(colors.background)
    }
}

// MARK: - Attachments (#368 G11)

/// The **View attachments** sheet (ADR-0044): the full set the compact ATTACHMENTS row summarises — each
/// synced file (open / caption / delete) and each on-device recording (play / delete). Adding a file stays
/// on the row (its picker is the host's glue); this sheet is view + manage only.
///
/// Framed like `ChangeDiffSheet` rather than iOS's `NavigationStack` + `List`: the navigation modifiers are
/// inert on macOS (no title, no way out), and a `List` inside a macOS sheet brings its own inset chrome that
/// fights the flat house style.
private struct AttachmentsSheet: View {
    let attachments: [Attachment]
    let onDeviceAttachments: [OnDeviceAttachment]
    @ObservedObject var player: OnDeviceAudioPlayer
    let onDelete: (String) -> Void
    let onSetCaption: (String, String) -> Void
    let onPlayToggle: (OnDeviceAttachment) -> Void
    let onDeleteOnDevice: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: 12) {
                    Text(L.string("tasks_detail_section_attachments"))
                        .font(.title3)
                        .accessibilityAddTraits(.isHeader)
                    ForEach(attachments, id: \.id) { attachment in
                        AttachmentRow(attachment: attachment, onDelete: onDelete, onSetCaption: onSetCaption)
                        Divider().overlay { colors.outlineVariant }
                    }
                    ForEach(onDeviceAttachments, id: \.id) { attachment in
                        OnDeviceAttachmentRow(
                            attachment: attachment,
                            player: player,
                            onPlayToggle: onPlayToggle,
                            onDelete: onDeleteOnDevice
                        )
                        Divider().overlay { colors.outlineVariant }
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(20)
            }
            Divider()
            HStack {
                Spacer()
                Button(L.string("common_done")) { dismiss() }
                    .keyboardShortcut(.cancelAction)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 12)
        }
        .frame(minWidth: 420, idealWidth: 480, minHeight: 300, idealHeight: 440)
        .background(colors.background)
    }
}

/// One synced attachment row: filename + size·type + optional caption (clicking opens the signed URL), a
/// Delete button, and an inline caption editor. Local editing state lives on the row so each toggles alone.
private struct AttachmentRow: View {
    let attachment: Attachment
    let onDelete: (String) -> Void
    let onSetCaption: (String, String) -> Void
    @State private var editing = false
    @State private var draft = ""
    @Environment(\.openURL) private var openURL
    @Environment(\.defernoColors) private var colors

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Button {
                    if let url = URL(string: attachment.url) { openURL(url) }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(attachment.filename)
                            .font(.body)
                            .foregroundStyle(colors.onSurface)
                            .lineLimit(1)
                            .truncationMode(.middle)
                        Text(L.format("tasks_detail_attachment_meta", byteLabel(attachment.size), attachment.mime))
                            .font(.caption).foregroundStyle(colors.inkMuted).lineLimit(1)
                        if let caption = attachment.caption, !caption.isEmpty {
                            Text(caption).font(.subheadline).foregroundStyle(colors.onSurface)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(L.format("common_open_named_cd", attachment.filename))
                // The middle-truncated filename is unreadable in full without a pointer affordance.
                .help(attachment.filename)
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

    private func byteLabel(_ bytes: Int64) -> String {
        ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file)
    }
}

/// One **on-device** attachment row (#272): a retained brain-dump recording, mirroring `AttachmentRow` minus
/// the signed-URL open + caption editor (these bytes never leave the device). A " · On device" marker sets it
/// apart from a synced row; **Play** shows only for audio, **Delete** removes the row and its bytes.
///
/// **Dead on macOS today, and deliberately so.** `onDeviceAttachments` is wired for every platform, but
/// nothing on macOS records a brain dump yet (Tranche 5 of #368), so this list is always empty here. It is
/// ported anyway: the alternative is a macOS-shaped fork of the sheet that has to be reconciled when
/// dictation lands.
private struct OnDeviceAttachmentRow: View {
    let attachment: OnDeviceAttachment
    @ObservedObject var player: OnDeviceAudioPlayer
    let onPlayToggle: (OnDeviceAttachment) -> Void
    let onDelete: (String) -> Void
    @Environment(\.defernoColors) private var colors

    var body: some View {
        let isPlayingThis = player.activeId == attachment.id && player.isPlaying
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                Text(attachment.filename)
                    .font(.body)
                    .foregroundStyle(colors.onSurface)
                    .lineLimit(1)
                    .truncationMode(.middle)
                Text(L.format("tasks_detail_attachment_meta_on_device", byteLabel(attachment.size), attachment.mime))
                    .font(.caption).foregroundStyle(colors.inkMuted).lineLimit(1)
                if let caption = attachment.caption, !caption.isEmpty {
                    Text(caption).font(.subheadline).foregroundStyle(colors.onSurface)
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

/// Plays one on-device brain-dump recording (#272) from in-memory bytes via `AVAudioPlayer`, retained here so
/// it isn't deallocated mid-clip. `activeId`/`isPlaying` drive the row's Play/Pause label; the delegate resets
/// them when the clip ends.
///
/// Unlike the iOS twin there is no `AVAudioSession` setup: that type doesn't exist on macOS (it is there to
/// flip the shared session off the brain-dump recorder's `.record` category), and `AVAudioPlayer(data:)`
/// plays directly.
final class OnDeviceAudioPlayer: NSObject, ObservableObject, AVAudioPlayerDelegate {
    /// The id of the clip currently loaded (playing or paused), or nil when nothing is loaded.
    @Published private(set) var activeId: String?
    @Published private(set) var isPlaying = false
    private var player: AVAudioPlayer?

    /// Load + start a clip (replacing any current one).
    func play(_ data: Data, id: String) {
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

// MARK: - Confirmation toast (#368 G24e)

/// A calm, auto-dismissing confirmation pill — the macOS twin of the iOS `ConfirmationToast`, styled off the
/// app's own `UndoSnackbar` (surface card, hairline, soft shadow) so the two transient surfaces match.
/// [token] drives it: each bump re-arms the hold and re-shows (so a repeated action retriggers it), and the
/// initial value never shows unbidden. VoiceOver is served by an explicit announcement at the call site, so
/// the transient visual is a11y-hidden here — it would otherwise steal focus without being reliably read.
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
        // Re-arm on each new bump; onChange skips the initial value so it never shows unbidden.
        .onChange(of: token) { _, _ in arm() }
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

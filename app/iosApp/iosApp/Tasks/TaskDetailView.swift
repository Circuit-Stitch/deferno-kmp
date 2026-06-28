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
    /// title + back chevron, so the in-pane `PaneHeader` is dropped (it would be a second title bar). The
    /// regular-width split keeps the default (the `PaneHeader` is the column's only header).
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
    // Plays on-device brain-dump recordings (#272) over the bytes the bridge hands back — no network, no signed URL.
    @StateObject private var audioPlayer = OnDeviceAudioPlayer()
    @Environment(\.defernoColors) private var colors

    init(component: TaskDetailComponent, showsHeader: Bool = true) {
        self.component = component
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        let title = value.task?.title ?? "Task"
        VStack(spacing: 0) {
            if showsHeader {
                // In single-pane the leading control returns to the list, so it reads as "Back".
                PaneHeader(title: title, onBack: { component.onCloseClicked() })
            }
            // Only while there's genuinely nothing on screen yet. Once the summary row is observed we
            // render it and let the background enrichment (description, #22) fill in silently — a
            // full-width strip flashing over an already-rendered task just shoves the body down and back
            // (the "loading blip" on content update).
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
        .background(Color(.systemBackground))
        .paneNavigationTitle(showsHeader ? nil : title)
    }

    @ViewBuilder
    private func taskBody(for task: Task, state value: TaskDetailState) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                // The "Everything in one place" title block (#231/#262): kind chip + headline title + a mono
                // meta line (ref · owner · time) + an overall subtask-progress bar, with the ⋮ kebab riding
                // top-right of the block (body-level, so it works for both the Tasks pane and the Plan drill).
                HStack(alignment: .top, spacing: 8) {
                    detailTitleBlock(task, state: value)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    overflowMenu
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
                Divider()
                attachmentsSection(value)
                Divider()
                commentsSection(value)
            }
            .padding(.horizontal, Layout.gutter)
            .padding(.vertical, 12)
        }
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { addAttachments(urls) }
        }
        // The kebab's "Add subtask" prompt: a calm inline title entry, mirroring the always-present
        // add-subtask field below — Add forwards a trimmed, non-empty title and clears.
        .alert("Add subtask", isPresented: $showingAddSubtask) {
            TextField("Subtask title", text: $kebabSubtask)
            Button("Cancel", role: .cancel) { kebabSubtask = "" }
            Button("Add") {
                let trimmed = kebabSubtask.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { component.onAddSubtask(title: trimmed) }
                kebabSubtask = ""
            }
        }
        // The destructive Delete, gated behind a confirmation (the kebab item is destructive).
        .confirmationDialog(
            "Delete this task?",
            isPresented: $confirmingDelete,
            titleVisibility: .visible
        ) {
            Button("Delete", role: .destructive) { component.onDelete() }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("This can't be undone.")
        }
    }

    // MARK: - Title block + overflow kebab (#262)

    /// The kind chip + headline title + mono meta line (ref · owner · time), plus — when the Task parents
    /// subtasks — an overall-status `<done> of <total>` progress bar right under the title, so the whole's
    /// completion is legible above the fold (#231). The kind here is always Task; it still wears the marker.
    @ViewBuilder
    private func detailTitleBlock(_ task: Task, state value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            TreeChip(text: "Task", tone: .accent)
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
                MonoMeta("\(value.subtaskDone) of \(value.subtaskTotal) done")
                    .accessibilityLabel("\(value.subtaskDone) of \(value.subtaskTotal) subtasks done")
                ProgressBarThin(fraction: Double(value.subtaskDone) / Double(value.subtaskTotal))
                    .accessibilityHidden(true)
            }
        }
    }

    /// The detail's ⋮ more-actions kebab: Add subtask (prompts for a title), Set aside (Dropped), and the
    /// destructive Delete (the caller gates it behind a confirm). Icon-only trigger; self-describing label.
    private var overflowMenu: some View {
        Menu {
            // The on-device impediment flow (Deferno#525) — "what's stopping you?" over this stuck Task.
            Button("Break this down") { component.onBreakdownClicked() }
            Button("Add subtask") { kebabSubtask = ""; showingAddSubtask = true }
            // The app's word for WorkingState.Dropped is "Set aside" (the chip editor below), so the kebab
            // uses the same term rather than web's "Drop" — one term per concept.
            Button("Set aside") { component.onSetWorkingState(target: WorkingState.dropped) }
            Button("Delete", role: .destructive) { confirmingDelete = true }
        } label: {
            DefernoIcon.moreVert.image
                .foregroundStyle(colors.onSurfaceVariant)
                .frame(width: Layout.minTouchTarget, height: Layout.minTouchTarget)
        }
        .accessibilityLabel("More actions")
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

    private func propertyRow(label: String, value: String) -> some View {
        HStack {
            Text(label).font(.subheadline).foregroundStyle(.secondary).frame(width: 72, alignment: .leading)
            Text(value).font(.body).foregroundStyle(value == "—" ? .secondary : .primary)
            Spacer()
        }
        .frame(minHeight: Layout.minTouchTarget)
    }

    /// The editable DUE row: a native compact `DatePicker` when a deadline is set (with a Clear button),
    /// else a "Set" button that seeds today's date. Confirming forwards the picked day; Clear forwards nil.
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
                .datePickerStyle(.compact)
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
            addField(placeholder: "Add a label…", text: $newLabel) { entry in
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
            SectionTitle("Subtasks", trailing: value.subtaskTotal > 0 ? "\(value.subtaskDone)/\(value.subtaskTotal)" : nil)
            if value.subtaskTotal > 0 {
                ProgressView(value: Double(value.subtaskDone), total: Double(value.subtaskTotal))
                    .accessibilityLabel("\(value.subtaskDone) of \(value.subtaskTotal) subtasks done")
            }
            ForEach(value.subtaskRows, id: \.task.stableKey) { row in
                subtaskRow(row)
            }
            addField(placeholder: "Add a subtask…", text: $newSubtask) { component.onAddSubtask(title: $0) }
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
                .accessibilityLabel(row.isExpanded ? "Collapse \(task.title)" : "Expand \(task.title)")
            } else {
                Spacer().frame(width: 16)
            }
            Button { component.onToggleSubtaskDone(subtask: task) } label: {
                Image(systemName: done ? "checkmark.square.fill" : "square")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(done ? "Mark \(task.title) not done" : "Mark \(task.title) done")
            Button { BridgeKt.openSubtask(component: component, subtask: task) } label: {
                Text(task.title)
                    .strikethrough(done)
                    .foregroundStyle(done ? .secondary : .primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open \(task.title)")
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
            SectionTitle("Attachments", trailing: "\(value.attachments.count + value.onDeviceAttachments.count)")
            Button(value.isUploadingAttachment ? "Uploading…" : "Add file") { importing = true }
                .font(.subheadline)
                .disabled(value.isUploadingAttachment)
            if value.attachments.isEmpty && value.onDeviceAttachments.isEmpty {
                Text("No attachments.").font(.subheadline).foregroundStyle(.secondary)
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
    private func commentsSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle("Activity", trailing: "\(value.comments.count)")
            commentComposer(isPosting: value.isPostingComment)
            if value.commentsLoading && value.comments.isEmpty {
                MutedLine("Loading…")
            } else if value.commentsError && value.comments.isEmpty {
                MutedLine("Couldn't load comments. Pull to refresh later.")
            } else if value.comments.isEmpty {
                MutedLine("No comments yet.")
            } else {
                ForEach(value.comments, id: \.id) { comment in
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

    @ViewBuilder
    private func commentComposer(isPosting: Bool) -> some View {
        VStack(alignment: .trailing, spacing: 4) {
            TextField("Add a comment…", text: $commentDraft, axis: .vertical)
                .lineLimit(2...5)
                .textFieldStyle(.roundedBorder)
                .disabled(isPosting)
                .accessibilityLabel("Comment body")
            Button {
                let trimmed = commentDraft.trimmingCharacters(in: .whitespacesAndNewlines)
                if !trimmed.isEmpty { component.onPostComment(body: trimmed); commentDraft = "" }
            } label: {
                Text(isPosting ? "Posting…" : "Post")
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
            Button("Add") { submit(text, onAdd) }
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
                        Text("\(byteLabel(attachment.size)) · \(attachment.mime)")
                            .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                        if let caption = attachment.caption, !caption.isEmpty {
                            Text(caption).font(.subheadline)
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Open \(attachment.filename)")
                Button("Delete") { onDelete(attachment.id) }
                    .font(.subheadline)
                    .accessibilityLabel("Delete \(attachment.filename)")
            }
            if editing {
                TextField("Caption", text: $draft).textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    Button("Cancel") { editing = false }
                    Button("Save") {
                        let trimmed = draft.trimmingCharacters(in: .whitespaces)
                        if !trimmed.isEmpty { onSetCaption(attachment.id, trimmed) }
                        editing = false
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespaces).isEmpty)
                }
            } else {
                Button(attachment.caption?.isEmpty == false ? "Edit caption" : "Add caption") {
                    draft = attachment.caption ?? ""
                    editing = true
                }
                .font(.subheadline)
                .accessibilityLabel("Edit caption for \(attachment.filename)")
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
                Text("\(byteLabel(attachment.size)) · \(attachment.mime) · On device")
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
                if let caption = attachment.caption, !caption.isEmpty {
                    Text(caption).font(.subheadline)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            if attachment.isAudio {
                Button(isPlayingThis ? "Pause" : "Play") { onPlayToggle(attachment) }
                    .font(.subheadline)
                    .accessibilityLabel("\(isPlayingThis ? "Pause" : "Play") \(attachment.filename)")
            }
            Button("Delete") { onDelete(attachment.id) }
                .font(.subheadline)
                .accessibilityLabel("Delete \(attachment.filename)")
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
                Text(isMine ? "You" : "Member").font(.caption.weight(.medium))
                Text(dateLabel).font(.caption2).foregroundStyle(.secondary)
            }
            if editing {
                TextField("Comment", text: $draft, axis: .vertical)
                    .lineLimit(2...5)
                    .textFieldStyle(.roundedBorder)
                HStack {
                    Spacer()
                    Button("Cancel") { editing = false }
                    Button("Save") {
                        let trimmed = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                        if !trimmed.isEmpty { onEdit(comment.id, trimmed) }
                        editing = false
                    }
                    .disabled(draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            } else {
                Text(comment.body ?? "🔒 Encrypted comment").font(.subheadline)
                if isMine {
                    HStack {
                        Button("Edit") { draft = comment.body ?? ""; editing = true }
                        Button("Delete") { onDelete(comment.id) }
                    }
                    .font(.subheadline)
                }
            }
        }
        .padding(.vertical, 6)
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
        Button { onSet(state) } label: {
            Text(state.label)
                .font(.subheadline)
                .padding(.horizontal, 12)
                .padding(.vertical, 8)
                .background(
                    selected ? Color.accentColor : Color(.secondarySystemFill),
                    in: Capsule()
                )
                .foregroundStyle(selected ? Color.white : Color.primary)
        }
        .frame(minHeight: Layout.minTouchTarget)
        .accessibilityLabel(selected ? "\(state.label), current working state" : "Set to \(state.label)")
    }
}

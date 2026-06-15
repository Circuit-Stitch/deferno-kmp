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

    init(component: TaskDetailComponent, showsHeader: Bool = true) {
        self.component = component
        self.showsHeader = showsHeader
        _state = StateObject(wrappedValue: StateFlowObserver(BridgeKt.taskDetailStateBridge(component: component)))
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
            ForEach(flatten(value.subtasks)) { item in
                subtaskRow(item)
            }
            addField(placeholder: "Add a subtask…", text: $newSubtask) { component.onAddSubtask(title: $0) }
        }
    }

    /// One row per flattened tree node, indented by depth. `node.children` is a Kotlin list of nodes; the
    /// recursion lives in plain Swift (a self-calling `@ViewBuilder` can't return an opaque recursive type).
    private func flatten(_ nodes: [SubtaskNode], depth: Int = 0) -> [FlatSubtask] {
        nodes.flatMap { node in
            [FlatSubtask(task: node.task, depth: depth)] + flatten(node.children, depth: depth + 1)
        }
    }

    private func subtaskRow(_ item: FlatSubtask) -> some View {
        let done = item.task.workingState === WorkingState.done
        return HStack(spacing: 8) {
            Button { component.onToggleSubtaskDone(subtask: item.task) } label: {
                Image(systemName: done ? "checkmark.square.fill" : "square")
                    .font(.title3)
            }
            .buttonStyle(.plain)
            .accessibilityLabel(done ? "Mark \(item.task.title) not done" : "Mark \(item.task.title) done")
            Button { BridgeKt.openSubtask(component: component, subtask: item.task) } label: {
                Text(item.task.title)
                    .strikethrough(done)
                    .foregroundStyle(done ? .secondary : .primary)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Open \(item.task.title)")
            Image(systemName: "chevron.right").font(.caption).foregroundStyle(.secondary)
        }
        .padding(.leading, CGFloat(item.depth) * 20)
        .frame(minHeight: Layout.minTouchTarget)
    }

    // MARK: - Attachments

    @ViewBuilder
    private func attachmentsSection(_ value: TaskDetailState) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            SectionTitle("Attachments", trailing: "\(value.attachments.count)")
            Button(value.isUploadingAttachment ? "Uploading…" : "Add file") { importing = true }
                .font(.subheadline)
                .disabled(value.isUploadingAttachment)
            if value.attachments.isEmpty {
                Text("No attachments.").font(.subheadline).foregroundStyle(.secondary)
            } else {
                ForEach(value.attachments, id: \.id) { attachment in
                    AttachmentRow(
                        attachment: attachment,
                        onDelete: { component.onDeleteAttachment(attachmentId: $0) },
                        onSetCaption: { component.onSetAttachmentCaption(attachmentId: $0, caption: $1) }
                    )
                }
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

/// One flattened node of the subtask tree — the child `Task` and its indentation depth.
private struct FlatSubtask: Identifiable {
    let task: Task
    let depth: Int
    var id: String { task.stableKey }
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
        let selected = state === current
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

import Deferno
import SwiftUI
import UniformTypeIdentifiers

/// The in-app **Help → Feedback** overlay (#375) — the SwiftUI twin of the shared `FeedbackForm`, a thin
/// renderer of `FeedbackComponent` (opened from Settings → Help & Feedback). A category chip picker over a
/// subject + body plus optional file attachments, sent through the **online-only** seam (ADR-0016): on
/// success the shell dismisses; offline shows a gentle "reconnect to send"; a server error shows a gentle
/// message — never a silent failure. It mirrors `NewItemView`'s structure exactly, so the two overlay forms
/// read the same.
///
/// Attachments use `.fileImporter`; each picked file's bytes cross to the shared component as `NSData`
/// (`ShellBridgeKt.feedbackAddAttachment`) — the iOS twin of Android's SAF + `ContentResolver` read.
struct FeedbackView: View {
    let component: FeedbackComponent
    @StateObject private var state: StateFlowObserver<FeedbackState>
    @State private var importing = false
    @Environment(\.defernoColors) private var colors

    init(component: FeedbackComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            HStack {
                Text(L.string("feedback_title")).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button(L.string("common_cancel")) { component.dismiss() }
            }
            .padding(.horizontal, Layout.gutter)
            .frame(minHeight: 56)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    categoryPicker(value)
                    TextField(L.string("feedback_subject_label"), text: Binding(get: { state.value.subject }, set: { component.setSubject(subject: $0) }))
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel(L.string("feedback_subject_label"))
                    TextField(L.string("feedback_body_label"), text: Binding(get: { state.value.body }, set: { component.setBody(body: $0) }), axis: .vertical)
                        .lineLimit(4...8)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel(L.string("feedback_body_cd"))
                    attachments(value)
                    statusMessage(value)
                    sendButton(value)
                }
                .padding(Layout.gutter)
            }
        }
        .background(colors.background)
        .fileImporter(isPresented: $importing, allowedContentTypes: [.item], allowsMultipleSelection: true) { result in
            if case .success(let urls) = result { urls.forEach(addAttachment) }
        }
    }

    @ViewBuilder
    private func attachments(_ value: FeedbackState) -> some View {
        Button(L.string("common_attach_files")) { importing = true }
            .font(.subheadline)
            .accessibilityLabel(L.string("common_attach_files"))
        ForEach(value.attachments.indices, id: \.self) { i in
            HStack {
                Text(value.attachments[i].filename)
                    .font(.subheadline).lineLimit(1).truncationMode(.middle)
                    .accessibilityLabel(L.format("feedback_attachment_cd", value.attachments[i].filename))
                Spacer()
                Button(L.string("common_remove")) { component.removeAttachment(index: Int32(i)) }
                    .font(.subheadline)
                    .accessibilityLabel(L.format("common_remove_named_cd", value.attachments[i].filename))
            }
        }
    }

    /// Read a picked file's bytes and hand them to the shared component. `.fileImporter` returns
    /// security-scoped URLs, so we bracket the read with start/stop access (no-op for in-app URLs).
    private func addAttachment(_ url: URL) {
        let scoped = url.startAccessingSecurityScopedResource()
        defer { if scoped { url.stopAccessingSecurityScopedResource() } }
        guard let data = try? Data(contentsOf: url) else { return }
        let mime = (try? url.resourceValues(forKeys: [.contentTypeKey]))?.contentType?.preferredMIMEType ?? "application/octet-stream"
        ShellBridgeKt.feedbackAddAttachment(component: component, filename: url.lastPathComponent, contentType: mime, data: data)
    }

    private func categoryPicker(_ value: FeedbackState) -> some View {
        HStack(spacing: 8) {
            ForEach(ShellBridgeKt.feedbackCategories().indices, id: \.self) { i in
                let category = ShellBridgeKt.feedbackCategories()[i]
                let selected = ShellBridgeKt.feedbackCategoriesEqual(a: value.category, b: category)
                Button(ShellBridgeKt.feedbackCategoryLabel(category: category)) { component.setCategory(category: category) }
                    .font(.subheadline)
                    .padding(.horizontal, 12).padding(.vertical, 8)
                    .background(selected ? colors.primary : colors.surfaceVariant, in: Capsule())
                    .foregroundStyle(selected ? colors.onPrimary : colors.onSurface)
            }
        }
        .accessibilityLabel(L.string("feedback_category_cd"))
    }

    @ViewBuilder
    private func statusMessage(_ value: FeedbackState) -> some View {
        if ShellBridgeKt.feedbackStatusIsOffline(state: value) {
            Text(L.string("feedback_offline_note"))
                .font(.footnote).foregroundStyle(colors.inkMuted)
                .accessibilityLabel(L.string("feedback_offline_note"))
        } else if let message = L.feedbackFailure(value) {
            Text(message).font(.footnote).foregroundStyle(colors.error)
        }
    }

    private func sendButton(_ value: FeedbackState) -> some View {
        Button { component.submit() } label: {
            Text(ShellBridgeKt.feedbackStatusIsSubmitting(state: value) ? L.string("feedback_submit_sending") : L.string("common_send"))
                .frame(maxWidth: .infinity).frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!value.canSubmit)
    }
}

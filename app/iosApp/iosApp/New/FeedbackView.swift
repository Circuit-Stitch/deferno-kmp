import Deferno
import SwiftUI

/// The in-app **Help → Feedback** overlay (#375) — the SwiftUI twin of the shared `FeedbackForm`, a thin
/// renderer of `FeedbackComponent` (opened from Settings → Help & Feedback). A category chip picker over a
/// subject + body, sent through the **online-only** seam (ADR-0016): on success the shell dismisses; offline
/// shows a gentle "reconnect to send"; a server error shows a gentle message — never a silent failure. It
/// mirrors `NewItemView`'s structure exactly, so the two overlay forms read the same.
///
/// ponytail: text-only — file attachments are omitted (the shared form's `onAttach == nil` path). Add a
/// `.fileImporter` + a Swift `Data`→`KotlinByteArray` bridge when iOS attachment uploads matter.
struct FeedbackView: View {
    let component: FeedbackComponent
    @StateObject private var state: StateFlowObserver<FeedbackState>
    @Environment(\.defernoColors) private var colors

    init(component: FeedbackComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(ShellBridgeKt.feedbackStateBridge(component: component)))
    }

    var body: some View {
        let value = state.value
        VStack(spacing: 0) {
            HStack {
                Text("Send feedback").font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Cancel") { component.dismiss() }
            }
            .padding(.horizontal, Layout.gutter)
            .frame(minHeight: 56)

            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    categoryPicker(value)
                    TextField("Subject", text: Binding(get: { state.value.subject }, set: { component.setSubject(subject: $0) }))
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("Subject")
                    TextField("What's going on?", text: Binding(get: { state.value.body }, set: { component.setBody(body: $0) }), axis: .vertical)
                        .lineLimit(4...8)
                        .textFieldStyle(.roundedBorder)
                        .accessibilityLabel("Feedback body")
                    statusMessage(value)
                    sendButton(value)
                }
                .padding(Layout.gutter)
            }
        }
        .background(colors.background)
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
        .accessibilityLabel("Category")
    }

    @ViewBuilder
    private func statusMessage(_ value: FeedbackState) -> some View {
        if ShellBridgeKt.feedbackStatusIsOffline(state: value) {
            Text("You're offline — reconnect to send. Nothing was queued.")
                .font(.footnote).foregroundStyle(colors.inkMuted)
                .accessibilityLabel("Reconnect to send")
        } else if let message = ShellBridgeKt.feedbackStatusFailedMessage(state: value) {
            Text(message).font(.footnote).foregroundStyle(colors.error)
        }
    }

    private func sendButton(_ value: FeedbackState) -> some View {
        Button { component.submit() } label: {
            Text(ShellBridgeKt.feedbackStatusIsSubmitting(state: value) ? "Sending…" : "Send")
                .frame(maxWidth: .infinity).frame(minHeight: Layout.minTouchTarget)
        }
        .buttonStyle(.borderedProminent)
        .disabled(!value.canSubmit)
    }
}

import Deferno
import SwiftUI

/// The **Assistant** chat surface (ADR-0040, #282) — the SwiftUI render of the shared `AssistantComponent`
/// state machine: the entitled-gated enable + egress-consent flow, the streamed conversation, the inline
/// proposal confirm card (never routed to the Inbox), and the multi-conversation switcher. It is a thin
/// view over `AssistantState`; all logic (availability, streaming, apply + re-sync, hydration) lives in the
/// shared component. The macOS twin of iosApp's `AssistantView` — rendered in the shell's detail pane, so
/// the window title/sidebar come from the shell; the chat-specific actions (new chat, conversations) sit in
/// a small in-view header rather than the shared window toolbar.
///
/// The turn streaming rides the `AssistantStream` seam wired in `DefernoRoot` (the Swift `MacAssistantTransport`
/// URLSession SSE reader); a unit host with no transport leaves the graceful `NONE`, so a send surfaces a
/// gentle error rather than hanging.
struct AssistantView: View {
    let component: AssistantComponent
    @StateObject private var state: StateFlowObserver<AssistantState>
    @Environment(\.defernoColors) private var colors
    @State private var showSwitcher = false

    init(component: AssistantComponent) {
        self.component = component
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    private var s: AssistantState { state.value }

    var body: some View {
        VStack(spacing: 0) {
            if s.available { header }
            content
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(colors.background)
        .sheet(isPresented: disclosurePresented) { consentSheet }
        .sheet(isPresented: $showSwitcher) { switcherSheet }
    }

    /// The chat affordances only make sense once the Assistant is on: a new chat + the conversation switcher.
    private var header: some View {
        HStack(spacing: 8) {
            Spacer()
            Button { showSwitcher = true } label: { Image(systemName: "clock.arrow.circlepath") }
                .help(L.string("assistant_conversations_title")).accessibilityLabel(L.string("assistant_conversations_title"))
            Button { component.onNewConversation() } label: { Image(systemName: "square.and.pencil") }
                .help(L.string("assistant_new_chat_title")).accessibilityLabel(L.string("assistant_new_chat_title"))
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
        .background(colors.surface)
    }

    // MARK: Top-level state routing

    @ViewBuilder
    private var content: some View {
        if s.needsEnable {
            enableCTA
        } else if s.available {
            chat
        } else {
            // Availability still loading (the gate is fetched before the chat shows). A calm placeholder —
            // the shell only surfaces this Destination once the Org is entitled, so this is brief.
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    // MARK: Enable + egress consent (entitled, not yet enabled)

    private var enableCTA: some View {
        VStack(spacing: 16) {
            Image(systemName: "sparkles").font(.system(size: 40)).foregroundStyle(colors.primary)
            Text(L.string("assistant_turn_on_title")).font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
            Text(s.disclosure)
                .font(.subheadline).foregroundStyle(colors.inkMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            Button { component.onEnableRequested() } label: {
                Label(L.string("assistant_enable_button"), systemImage: "checkmark")
            }
            .buttonStyle(.borderedProminent)
            .disabled(s.enabling)
            .padding(.top, 8)
            if s.enabling { ProgressView() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, Layout.gutter)
    }

    /// The egress-consent confirmation, shown after the person taps Enable (`showingDisclosure`). Accepting
    /// performs the server enablement; dismissing declines.
    private var consentSheet: some View {
        VStack(spacing: 20) {
            Image(systemName: "lock.shield").font(.system(size: 36)).foregroundStyle(colors.primary)
            Text(L.string("assistant_before_enable_title")).font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
            Text(s.disclosure)
                .font(.subheadline).foregroundStyle(colors.inkMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            HStack(spacing: 10) {
                Button(L.string("assistant_consent_not_now")) { component.onConsentDeclined() }
                Button { component.onConsentAccepted() } label: { Text(L.string("assistant_consent_accept")) }
                    .buttonStyle(.borderedProminent)
                    .disabled(s.enabling)
            }
            .padding(.top, 8)
        }
        .padding(28)
        .frame(minWidth: 360)
        .background(colors.background)
    }

    // MARK: Chat (available)

    private var chat: some View {
        VStack(spacing: 0) {
            if !s.online { banner(L.string("assistant_offline_note"), tint: colors.inkMuted) }
            if s.usageExhausted {
                banner(L.string("assistant_quota_note"), tint: colors.error)
            }
            if let error = L.assistantError(s) {
                banner(error, tint: colors.error) { component.onDismissError() }
            }
            messageList
            if let proposal = s.pendingProposal { proposalCard(proposal) }
            composer
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    if s.messages.isEmpty {
                        emptyChat
                    } else {
                        ForEach(s.messages, id: \.id) { message in
                            messageBubble(message).id(message.id)
                        }
                    }
                    // The tool actions the Assistant took this turn — so each move/read is visible, not just
                    // the final text. Enumerated id: the same tool can be called twice (duplicate strings).
                    ForEach(Array(s.actions.enumerated()), id: \.offset) { _, action in
                        actionRow(action)
                    }
                    if s.streaming { typingIndicator }
                }
                .padding(.horizontal, Layout.gutter).padding(.vertical, 12)
            }
            // Keep the latest line in view as the reply streams in / a turn is sent.
            .onChange(of: s.messages.count) { _ in scrollToEnd(proxy) }
            .onChange(of: s.actions.count) { _ in scrollToEnd(proxy) }
            .onChange(of: s.streaming) { _ in scrollToEnd(proxy) }
        }
    }

    private var emptyChat: some View {
        VStack(spacing: 8) {
            Image(systemName: "sparkles").font(.system(size: 32)).foregroundStyle(colors.primary)
            Text(L.string("assistant_empty_title")).font(.headline).foregroundStyle(colors.onSurface)
            Text(L.string("assistant_empty_body"))
                .font(.subheadline).foregroundStyle(colors.inkMuted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.top, 48)
    }

    private func messageBubble(_ message: ChatMessage) -> some View {
        let isUser = ShellBridgeKt.chatMessageIsUser(message: message)
        return HStack {
            if isUser { Spacer(minLength: 40) }
            markdownText(message.text)
                .font(.body)
                .foregroundStyle(isUser ? colors.onPrimary : colors.onSurface)
                .textSelection(.enabled) // select/copy any bubble (sent or reply)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(
                    isUser ? colors.primary : colors.surfaceVariant,
                    in: RoundedRectangle(cornerRadius: 18),
                )
            if !isUser { Spacer(minLength: 40) }
        }
        .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
    }

    /// Render a bubble as inline markdown (bold/italic/code/links), preserving the streamed line breaks.
    /// SwiftUI's parser is inline-only, so block markdown — the table the server's LLM emits for a tool
    /// result — stays as plain text; proper task cards (with Deferno refs) need a structured items frame from
    /// the server, not the freeform `text` stream (Deferno#485). ponytail: native AttributedString, no md lib.
    private func markdownText(_ raw: String) -> Text {
        if let parsed = try? AttributedString(
            markdown: raw,
            options: .init(interpretedSyntax: .inlineOnlyPreservingWhitespace,
                           failurePolicy: .returnPartiallyParsedIfPossible)
        ) {
            return Text(parsed)
        }
        return Text(raw)
    }

    /// One autonomous tool action the Assistant took (e.g. `list_items`) — a muted activity line, not a
    /// chat bubble, so the transcript reads as "what it did" without competing with the reply text.
    private func actionRow(_ action: String) -> some View {
        HStack(spacing: 6) {
            Image(systemName: "wrench.and.screwdriver").font(.caption2)
            Text(action).font(.footnote.monospaced())
            Spacer()
        }
        .foregroundStyle(colors.inkMuted)
        .padding(.vertical, 2)
    }

    private var typingIndicator: some View {
        HStack(spacing: 6) {
            ProgressView().controlSize(.small)
            Text(L.string("breakdown_thinking")).font(.footnote).foregroundStyle(colors.inkMuted)
        }
        .padding(.vertical, 4)
    }

    /// The inline proposal confirm card (ADR-0040) — a gated change applied server-side on confirm; never
    /// routed to the Inbox. Rejecting is a local discard.
    private func proposalCard(_ proposal: AssistantProposal) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.seal").foregroundStyle(colors.primary)
                Text(L.string("assistant_confirm_change_title")).font(.subheadline.weight(.semibold)).foregroundStyle(colors.onSurface)
            }
            Text(proposal.summary).font(.body).foregroundStyle(colors.onSurface)
            HStack(spacing: 10) {
                Button { component.onConfirmProposal() } label: {
                    Text(L.string("assistant_apply_button")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(s.applyingProposal)
                Button { component.onRejectProposal() } label: {
                    Text(L.string("common_dismiss")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .disabled(s.applyingProposal)
            }
            if s.applyingProposal { ProgressView() }
        }
        .padding(16)
        .background(colors.surfaceVariant, in: RoundedRectangle(cornerRadius: 16))
        .padding(.horizontal, Layout.gutter).padding(.bottom, 8)
    }

    private var composer: some View {
        HStack(spacing: 10) {
            TextField(L.string("assistant_message_placeholder"), text: composerText, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(colors.surfaceVariant, in: RoundedRectangle(cornerRadius: 20))
                .disabled(!s.composerEnabled)
            if s.streaming {
                Button { component.onCancelTurn() } label: {
                    Image(systemName: "stop.circle.fill").font(.system(size: 28))
                }
                .accessibilityLabel(L.string("braindump_stop"))
                .foregroundStyle(colors.error)
            } else {
                Button { component.onSend() } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 28))
                }
                .accessibilityLabel(L.string("common_send"))
                .foregroundStyle(s.canSend ? colors.primary : colors.inkMuted)
                .disabled(!s.canSend)
            }
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
        .background(colors.background)
    }

    // MARK: Conversation switcher

    private var switcherSheet: some View {
        VStack(spacing: 0) {
            HStack {
                Text(L.string("assistant_conversations_title")).font(.headline).foregroundStyle(colors.onSurface)
                Spacer()
                Button(L.string("calendar_action_done")) { showSwitcher = false }
            }
            .padding(Layout.gutter)
            List {
                Button {
                    component.onNewConversation(); showSwitcher = false
                } label: {
                    Label(L.string("assistant_new_chat_title"), systemImage: "square.and.pencil")
                }
                if !s.conversations.isEmpty {
                    Section(L.string("assistant_recent_section")) {
                        ForEach(Array(s.conversations.enumerated()), id: \.offset) { _, conversation in
                            conversationRow(conversation)
                        }
                    }
                }
            }
        }
        .frame(minWidth: 360, minHeight: 360)
        .background(colors.background)
    }

    private func conversationRow(_ conversation: Conversation) -> some View {
        let key = ShellBridgeKt.assistantConversationKey(conversation: conversation)
        let isActive = key == ShellBridgeKt.assistantActiveConversationKey(state: s)
        return Button {
            ShellBridgeKt.assistantSelectConversation(component: component, conversation: conversation)
            showSwitcher = false
        } label: {
            HStack {
                Text(ShellBridgeKt.assistantConversationTitle(conversation: conversation))
                    .foregroundStyle(colors.onSurface).lineLimit(1)
                Spacer()
                if isActive { Image(systemName: "checkmark").foregroundStyle(colors.primary) }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: Bits

    private var composerText: Binding<String> {
        Binding(get: { s.composer }, set: { component.onComposerChanged(text: $0) })
    }

    private var disclosurePresented: Binding<Bool> {
        Binding(get: { s.showingDisclosure }, set: { presented in if !presented { component.onConsentDeclined() } })
    }

    private func banner(_ text: String, tint: Color, onDismiss: (() -> Void)? = nil) -> some View {
        HStack(spacing: 8) {
            Text(text).font(.footnote).foregroundStyle(tint)
            Spacer()
            if let onDismiss {
                Button { onDismiss() } label: { Image(systemName: "xmark").font(.caption) }
                    .buttonStyle(.borderless)
                    .foregroundStyle(tint).accessibilityLabel(L.string("common_dismiss"))
            }
        }
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
        .background(colors.surfaceVariant)
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        guard let last = s.messages.last else { return }
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
    }
}

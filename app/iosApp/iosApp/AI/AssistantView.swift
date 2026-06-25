import Deferno
import SwiftUI

/// The **Assistant** chat surface (ADR-0040, #282) — the SwiftUI render of the shared `AssistantComponent`
/// state machine: the entitled-gated enable + egress-consent flow, the streamed conversation, the inline
/// proposal confirm card (never routed to the Inbox), and the multi-conversation switcher. It is a thin
/// view over `AssistantState`; all logic (availability, streaming, apply + re-sync, hydration) lives in the
/// shared component. Owns its own native nav chrome (the design's iOS carve-out), so the shell renders it
/// outside the shared `ChromeToolbar` (like `TasksScreen`), threading only the drawer `onMenu` in.
///
/// The turn streaming itself rides the `AssistantStream` seam wired in `DefernoRoot`; until the live SSE
/// transport is reconciled the seam is the graceful `NONE`, so a send surfaces a gentle error rather than
/// hanging — every other path (enable, consent, switcher, hydration, proposal apply) is already live.
struct AssistantView: View {
    let component: AssistantComponent
    /// Opens the shell's reveal drawer (the leading ☰), threaded from `MainShellView` like `TasksScreen`.
    var onMenu: () -> Void = {}
    @StateObject private var state: StateFlowObserver<AssistantState>
    @Environment(\.defernoColors) private var colors
    @State private var showSwitcher = false

    init(component: AssistantComponent, onMenu: @escaping () -> Void = {}) {
        self.component = component
        self.onMenu = onMenu
        _state = StateObject(wrappedValue: StateFlowObserver(component.state))
    }

    private var s: AssistantState { state.value }

    var body: some View {
        NavigationStack {
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(colors.background)
                .navigationTitle("Assistant")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .navigationBarLeading) {
                        Button { onMenu() } label: { Image(systemName: "line.3.horizontal") }
                            .accessibilityLabel("Menu")
                    }
                    // The chat affordances only make sense once the Assistant is on.
                    if s.available {
                        ToolbarItemGroup(placement: .navigationBarTrailing) {
                            Button { showSwitcher = true } label: { Image(systemName: "clock.arrow.circlepath") }
                                .accessibilityLabel("Conversations")
                            Button { component.onNewConversation() } label: { Image(systemName: "square.and.pencil") }
                                .accessibilityLabel("New chat")
                        }
                    }
                }
        }
        .sheet(isPresented: disclosurePresented) { consentSheet }
        .sheet(isPresented: $showSwitcher) { switcherSheet }
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
            Text("Turn on the Assistant").font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
            Text(s.disclosure)
                .font(.subheadline).foregroundStyle(colors.inkMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            PrimaryActionButton(title: "Enable Assistant", icon: .check) { component.onEnableRequested() }
                .padding(.horizontal, 48).padding(.top, 8)
                .disabled(s.enabling)
            if s.enabling { ProgressView() }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, Layout.gutter)
    }

    /// The egress-consent confirmation, shown after the person taps Enable (`showingDisclosure`). Accepting
    /// performs the server enablement; dismissing declines.
    private var consentSheet: some View {
        VStack(spacing: 20) {
            Capsule().fill(colors.outlineVariant).frame(width: 36, height: 5).padding(.top, 10)
            Image(systemName: "lock.shield").font(.system(size: 36)).foregroundStyle(colors.primary)
            Text("Before you enable").font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
            Text(s.disclosure)
                .font(.subheadline).foregroundStyle(colors.inkMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
            Spacer()
            VStack(spacing: 10) {
                PrimaryActionButton(title: "I understand — enable", icon: .check) { component.onConsentAccepted() }
                    .disabled(s.enabling)
                Button("Not now") { component.onConsentDeclined() }
                    .foregroundStyle(colors.inkMuted)
            }
            .padding(.horizontal, 24).padding(.bottom, 24)
        }
        .frame(maxWidth: .infinity)
        .background(colors.background.ignoresSafeArea())
        .presentationDetents([.medium])
    }

    // MARK: Chat (available)

    private var chat: some View {
        VStack(spacing: 0) {
            if !s.online { banner("You're offline — reconnect to continue the conversation.", tint: colors.inkMuted) }
            if s.usageExhausted {
                banner("You've used this month's Assistant quota. It resets next month.", tint: colors.error)
            }
            if let error = s.error {
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
                    if s.streaming { typingIndicator }
                }
                .padding(.horizontal, Layout.gutter).padding(.vertical, 12)
            }
            // Keep the latest line in view as the reply streams in / a turn is sent.
            .onChange(of: s.messages.count) { _ in scrollToEnd(proxy) }
            .onChange(of: s.streaming) { _ in scrollToEnd(proxy) }
        }
    }

    private var emptyChat: some View {
        VStack(spacing: 8) {
            Image(systemName: "sparkles").font(.system(size: 32)).foregroundStyle(colors.primary)
            Text("Ask me about your tasks").font(.headline).foregroundStyle(colors.onSurface)
            Text("I can find things, make changes, and help you plan.")
                .font(.subheadline).foregroundStyle(colors.inkMuted).multilineTextAlignment(.center)
        }
        .frame(maxWidth: .infinity).padding(.top, 48)
    }

    private func messageBubble(_ message: ChatMessage) -> some View {
        let isUser = ShellBridgeKt.chatMessageIsUser(message: message)
        return HStack {
            if isUser { Spacer(minLength: 40) }
            Text(message.text)
                .font(.body)
                .foregroundStyle(isUser ? colors.onPrimary : colors.onSurface)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(
                    isUser ? colors.primary : colors.surfaceVariant,
                    in: RoundedRectangle(cornerRadius: 18),
                )
            if !isUser { Spacer(minLength: 40) }
        }
        .frame(maxWidth: .infinity, alignment: isUser ? .trailing : .leading)
    }

    private var typingIndicator: some View {
        HStack(spacing: 6) {
            ProgressView().controlSize(.small)
            Text("Thinking…").font(.footnote).foregroundStyle(colors.inkMuted)
        }
        .padding(.vertical, 4)
    }

    /// The inline proposal confirm card (ADR-0040) — a gated change applied server-side on confirm; never
    /// routed to the Inbox. Rejecting is a local discard.
    private func proposalCard(_ proposal: AssistantProposal) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                Image(systemName: "checkmark.seal").foregroundStyle(colors.primary)
                Text("Confirm this change").font(.subheadline.weight(.semibold)).foregroundStyle(colors.onSurface)
            }
            Text(proposal.summary).font(.body).foregroundStyle(colors.onSurface)
            HStack(spacing: 10) {
                Button { component.onConfirmProposal() } label: {
                    Text("Apply").frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(s.applyingProposal)
                Button { component.onRejectProposal() } label: {
                    Text("Dismiss").frame(maxWidth: .infinity)
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
            TextField("Message", text: composerText, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(colors.surfaceVariant, in: RoundedRectangle(cornerRadius: 20))
                .disabled(!canType)
            if s.streaming {
                Button { component.onCancelTurn() } label: {
                    Image(systemName: "stop.circle.fill").font(.system(size: 30))
                }
                .accessibilityLabel("Stop")
                .foregroundStyle(colors.error)
            } else {
                Button { component.onSend() } label: {
                    Image(systemName: "arrow.up.circle.fill").font(.system(size: 30))
                }
                .accessibilityLabel("Send")
                .foregroundStyle(s.canSend ? colors.primary : colors.inkMuted)
                .disabled(!s.canSend)
            }
        }
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
        .background(colors.background)
    }

    // MARK: Conversation switcher

    private var switcherSheet: some View {
        NavigationStack {
            List {
                Button {
                    component.onNewConversation(); showSwitcher = false
                } label: {
                    Label("New conversation", systemImage: "square.and.pencil")
                }
                if !s.conversations.isEmpty {
                    Section("Recent") {
                        ForEach(Array(s.conversations.enumerated()), id: \.offset) { _, conversation in
                            conversationRow(conversation)
                        }
                    }
                }
            }
            .navigationTitle("Conversations")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Done") { showSwitcher = false }
                }
            }
        }
        .presentationDetents([.medium, .large])
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
        }
    }

    // MARK: Bits

    private var canType: Bool {
        s.available && s.online && !s.streaming && !s.usageExhausted && s.pendingProposal == nil
    }

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
                    .foregroundStyle(tint).accessibilityLabel("Dismiss")
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

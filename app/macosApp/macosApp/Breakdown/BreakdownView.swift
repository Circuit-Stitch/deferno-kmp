import Deferno
import SwiftUI

/// The macOS-native **Breakdown** surface (Deferno#525, #368 G10) — the SwiftUI render of the on-device
/// impediment flow: *"what's stopping you?"* → the model classifies the answer → the deterministic
/// `BreakdownEngine` routes one concrete move → recurse → stop when everything's Ready. All logic lives in
/// the engine; this is a thin chat over it (mirrors `AI/AssistantView.swift`, whose macOS chat conventions
/// — the composer, the typing indicator, the bubble — it deliberately reuses rather than re-inventing).
///
/// The engine needs the root `ItemContext` at construction, but `component.target` resolves async — so this
/// gates on availability: a spinner until the row resolves, then an inner view builds the engine once from
/// the resolved item. Before any of that it checks Apple Intelligence (the on-device classifier needs it),
/// through the single `AppleIntelligence` gate hoisted into `AI/MacInference.swift`.
///
/// The macOS twin of iosApp's `Breakdown/BreakdownView.swift`. The engine, its types and the classifier are
/// byte-identical ports; **this file is the one that had to change**, because iOS presents the flow inside a
/// `NavigationStack` and macOS presents it as a shell sheet. Every divergence below is marked at its site.
struct BreakdownView: View {
    let component: BreakdownComponent
    @StateObject private var target: OptionalStateFlowObserver<Task>
    @Environment(\.defernoColors) private var colors

    init(component: BreakdownComponent) {
        self.component = component
        _target = StateObject(wrappedValue: OptionalStateFlowObserver(component.target))
    }

    var body: some View {
        VStack(spacing: 0) {
            header
            content
                .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        // A macOS sheet has no navigation chrome to size it, and the shell presents this one with no other
        // constraint — so give the chat an explicit floor, or an empty transcript collapses it to the
        // intrinsic height of one bubble. Same idiom as the other macOS overlay sheets (AssistantView's
        // conversation switcher, DraftExtractorView).
        .frame(minWidth: 520, minHeight: 560)
        .background(colors.background)
    }

    /// iOS wraps the flow in a `NavigationStack` and hangs Close off a `.navigationBarLeading` toolbar item
    /// under an inline `.navigationBarTitleDisplayMode` — all three are iOS-only APIs or meaningless in a
    /// macOS sheet, which has no navigation bar at all. The macOS overlays instead carry their own header
    /// row (`Search/SearchView.swift`, `New/NewItemView.swift` both do, to the pixel); this matches them.
    /// A platform-forced divergence, not a redesign.
    private var header: some View {
        HStack {
            Text(L.string("breakdown_title")).font(.title2.weight(.semibold)).accessibilityAddTraits(.isHeader)
            Spacer()
            Button(L.string("common_close")) { component.onClose() }
        }
        .padding(.horizontal, Layout.gutter)
        .frame(minHeight: 56)
    }

    @ViewBuilder
    private var content: some View {
        if !AppleIntelligence.isAvailable {
            unavailable
        } else if let task = target.value {
            // The engine snapshots the root context at construction (`@StateObject` builds it once); a later
            // title edit on the live row doesn't rebuild it — the flow stays on the item you opened.
            BreakdownChat(
                component: component,
                root: ItemContext(id: component.taskId, title: task.title, notes: task.itemDescription)
            )
        } else {
            ProgressView().frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }

    private var unavailable: some View {
        VStack(spacing: 16) {
            Image(systemName: "sparkles").font(.system(size: 40)).foregroundStyle(colors.primary)
            Text(L.string("breakdown_needs_ai_title"))
                .font(.title3.weight(.semibold)).foregroundStyle(colors.onSurface)
                .multilineTextAlignment(.center)
            Text(L.string("breakdown_needs_ai_body"))
                .font(.subheadline).foregroundStyle(colors.inkMuted)
                .multilineTextAlignment(.center).padding(.horizontal, 24)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(.horizontal, Layout.gutter)
    }
}

/// The chat itself, built once the root item has resolved. Owns the `@MainActor` `BreakdownEngine` as a
/// `@StateObject`, so each intent is a `_Concurrency.Task { await … }` (`Task` alone resolves to the
/// exported Kotlin `Deferno.Task`).
private struct BreakdownChat: View {
    let component: BreakdownComponent
    @StateObject private var engine: BreakdownEngine
    @State private var draft = ""
    @State private var addedToPlan = false
    @Environment(\.defernoColors) private var colors

    init(component: BreakdownComponent, root: ItemContext) {
        self.component = component
        _engine = StateObject(wrappedValue: BreakdownEngine(
            root: root,
            classifier: FoundationModelsClassifier(),
            moves: KotlinBreakdownMoves(actions: component.actions)
        ))
    }

    var body: some View {
        VStack(spacing: 0) {
            focusLine
            messageList
            footer
        }
    }

    /// iOS rebinds `.navigationTitle` to whichever node is in focus as the flow recurses into the parts it
    /// just created. A macOS sheet has no title bar to rebind, so the focus surfaces as a persistent line
    /// under the (static) sheet title — same information, one fewer moving part.
    ///
    /// No catalog key: this renders the *item's own title*, which is user data. The Compose surfaces prefix
    /// it with `breakdown_focus_prefix`, but that key is a deliberate Compose-only entry in
    /// `app/shared-l10n/l10n-parity-overrides.txt` and is **absent from the Apple catalog** — iOS doesn't
    /// use it either (its nav title is bare), so don't reach for it here.
    @ViewBuilder
    private var focusLine: some View {
        if let focus = engine.focusTitle {
            Text(focus)
                .font(.subheadline.weight(.medium))
                .foregroundStyle(colors.onSurfaceVariant)
                .lineLimit(1)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, Layout.gutter)
                .padding(.bottom, 6)
        }
    }

    private var messageList: some View {
        ScrollViewReader { proxy in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 10) {
                    ForEach(engine.messages) { message in
                        bubble(message).id(message.id)
                    }
                    if engine.phase == .working { typingIndicator }
                }
                .padding(.horizontal, Layout.gutter).padding(.vertical, 12)
            }
            // Keep the newest line in view as the conversation grows. Same trigger as iOS, but the
            // **zero-argument** `onChange` — NOT iOS's `{ _ in … }` form, which is deprecated at our macOS
            // 14.0 deployment target and would fail the build outright (`SWIFT_TREAT_WARNINGS_AS_ERRORS=YES`
            // in .github/workflows/macos.yml). Matches AI/AssistantView.swift. The only forced divergence
            // here is the closure arity; watching `engine.phase` as well would be near-dead weight (every
            // transition but one appends a message in the same update) and would still scroll to the last
            // *message*, never the typing indicator that renders after it.
            .onChange(of: engine.messages.count) { scrollToEnd(proxy) }
        }
    }

    @ViewBuilder
    private var footer: some View {
        switch engine.phase {
        case .asking, .working:
            composer
        case .confirmingDrop:
            dropConfirm
        case .finished(let outcome):
            finishedBar(outcome)
        }
    }

    // MARK: Phases

    private var composer: some View {
        HStack(spacing: 10) {
            TextField(L.string("breakdown_composer_placeholder"), text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .textFieldStyle(.plain)
                .padding(.horizontal, 14).padding(.vertical, 10)
                .background(colors.surfaceVariant, in: RoundedRectangle(cornerRadius: 20))
                .disabled(engine.phase != .asking)
            Button { send() } label: {
                // 28pt, not iOS's 30 — the macOS chat composer's glyph size (AI/AssistantView.swift).
                Image(systemName: "arrow.up.circle.fill").font(.system(size: 28))
            }
            .accessibilityLabel(L.string("common_send"))
            .foregroundStyle(canSend ? colors.primary : colors.inkMuted)
            .disabled(!canSend)
        }
        // AppKit bezels an icon-only Button unless the style says otherwise, so the send arrow would sit in
        // a push-button box. iOS needs no such thing; the macOS Assistant composer carries exactly this.
        .buttonStyle(.borderless)
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
        .background(colors.background)
    }

    /// The one destructive move (PRD #26): an explicit yes/no before a drop, never a swipe-by.
    private var dropConfirm: some View {
        HStack(spacing: 10) {
            Button { confirmDrop(false) } label: { Text(L.string("breakdown_keep_it")).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
            Button { confirmDrop(true) } label: { Text(L.string("breakdown_let_it_go")).frame(maxWidth: .infinity) }
                .buttonStyle(.borderedProminent)
        }
        // `.controlSize(.large)` is the macOS full-width CTA idiom this app already uses (the detail pane's
        // Add to plan, Tasks/TaskDetailView.swift) — iOS's default metrics read undersized on a Mac.
        .controlSize(.large)
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
    }

    @ViewBuilder
    private func finishedBar(_ outcome: BreakdownEngine.Outcome) -> some View {
        VStack(spacing: 10) {
            if outcome == .ready {
                Button {
                    addedToPlan = true
                    _Concurrency.Task { await engine.addRootToPlan() }
                } label: {
                    Text(L.string("tasks_menu_add_to_plan")).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(addedToPlan)
            }
            Button { component.onClose() } label: { Text(L.string("calendar_action_done")).frame(maxWidth: .infinity) }
                .buttonStyle(.bordered)
        }
        .controlSize(.large) // same macOS CTA sizing as dropConfirm
        .padding(.horizontal, Layout.gutter).padding(.vertical, 8)
    }

    // MARK: Intents (each hops to the engine's main actor via _Concurrency.Task)

    private var canSend: Bool {
        engine.phase == .asking && !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    private func send() {
        let text = draft
        draft = ""
        _Concurrency.Task { await engine.submit(answer: text) }
    }

    private func confirmDrop(_ yes: Bool) {
        _Concurrency.Task { await engine.confirmDrop(yes) }
    }

    // MARK: Bits

    private func bubble(_ message: BreakdownMessage) -> some View {
        let isUser = message.role == .user
        return HStack {
            if isUser { Spacer(minLength: 40) }
            Text(message.text)
                .font(.body)
                .foregroundStyle(isUser ? colors.onPrimary : colors.onSurface)
                .textSelection(.enabled)
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
            Text(L.string("breakdown_thinking")).font(.footnote).foregroundStyle(colors.inkMuted)
        }
        .padding(.vertical, 4)
    }

    private func scrollToEnd(_ proxy: ScrollViewProxy) {
        guard let last = engine.messages.last else { return }
        withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(last.id, anchor: .bottom) }
    }
}

/// Conforms the SKIE-bridged Kotlin `BreakdownActions` to the engine's native `BreakdownMoves`, so every
/// structural move the deterministic flow makes rides the shared offline-first outbox (#185) — never a
/// reimplemented write path. Kotlin `suspend` → Swift `async throws`; a thrown move degrades to a no-op
/// (the move is fire-and-persist; the outbox owns the retry). `@unchecked Sendable`: the existential holds
/// a Kotlin reference the engine only ever touches on its main actor.
struct KotlinBreakdownMoves: BreakdownMoves, @unchecked Sendable {
    let actions: BreakdownActions

    func captureSubtask(under parentID: String, title: String) async -> String? {
        try? await actions.captureSubtask(parentId: parentID, title: title)
    }

    func drop(_ id: String) async { try? await actions.drop(taskId: id) }

    func addToPlan(_ id: String) async { try? await actions.addToPlan(taskId: id) }
}

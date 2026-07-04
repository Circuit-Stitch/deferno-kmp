import Combine
import Foundation

/// The deterministic **Breakdown** state machine (Deferno#525), native to iOS. It runs the impediment
/// conversation — *"what's stopping you?"* → classify the answer → route to one concrete move → recurse
/// into any new parts → stop when everything is Ready. The model is consulted at **exactly one point**
/// (`classifier.classify`); move selection, recursion, and the terminal check are LLM-free, so the whole
/// flow is unit-testable with a scripted classifier + a spy `BreakdownMoves`.
///
/// Intents are `async` and applied inline (no fire-and-forget `Task`), so the View wraps each call in a
/// `Task { … }` and tests just `await` them — every transition is deterministic.
@MainActor
final class BreakdownEngine: ObservableObject {

    enum Phase: Sendable, Equatable {
        /// Waiting for the person's free-text answer to "what's stopping you from <current>?".
        case asking
        /// Classifying / applying a move — input disabled.
        case working
        /// Proposed the one destructive move (a drop); waiting for an explicit yes/no (PRD #26).
        case confirmingDrop
        /// Terminal: every leaf Ready / dropped / bailed out.
        case finished(Outcome)
    }

    enum Outcome: Sendable, Equatable { case ready, dropped, bailed }

    @Published private(set) var messages: [BreakdownMessage] = []
    @Published private(set) var phase: Phase = .working

    /// The title currently in focus — the View's header ("Breaking down: …").
    var focusTitle: String? { current?.title }

    private struct Node: Equatable { let id: String; let title: String }

    private let classifier: ImpedimentClassifier
    private let moves: BreakdownMoves
    private let makeID: () -> UUID

    private let rootID: String
    private let rootNotes: String?

    /// Depth-first worklist of parts still to break down; `current` is the focus we're asking about.
    private var stack: [Node] = []
    private var current: Node?
    private var pendingDrop: Node?
    private var rootDropped = false

    init(
        root: ItemContext,
        classifier: ImpedimentClassifier,
        moves: BreakdownMoves,
        makeID: @escaping () -> UUID = UUID.init
    ) {
        self.classifier = classifier
        self.moves = moves
        self.makeID = makeID
        self.rootID = root.id
        self.rootNotes = root.notes
        ask(Node(id: root.id, title: root.title))
    }

    // MARK: - Intents

    /// The person answered "what's stopping you?". Classify it and apply the routed move.
    func submit(answer: String) async {
        let text = answer.trimmingCharacters(in: .whitespacesAndNewlines)
        guard case .asking = phase, let node = current, !text.isEmpty else { return }
        append(.user, text)
        phase = .working

        let classification: ImpedimentClassification
        do {
            classification = try await classifier.classify(answer: text, item: context(for: node))
        } catch {
            // A transient on-device failure: never strand the person — let them rephrase (availability is
            // checked before the engine is built, so this is a generation hiccup, not "no Apple Intelligence").
            append(.assistant, L.string("breakdown_msg_classifier_retry"))
            current = node
            phase = .asking
            return
        }
        await route(classification, node: node)
    }

    /// Answer the drop offer (PRD #14/#26): an explicit yes really drops; no keeps it and re-asks.
    func confirmDrop(_ yes: Bool) async {
        guard case .confirmingDrop = phase, let node = pendingDrop else { return }
        pendingDrop = nil
        phase = .working
        if yes {
            await moves.drop(node.id)
            if node.id == rootID { rootDropped = true }
            append(.assistant, L.format("breakdown_msg_dropped", node.title))
            advance()
        } else {
            append(.assistant, L.format("breakdown_msg_kept_reask", node.title))
            current = node
            phase = .asking
        }
    }

    /// The Ready terminal's "Add to plan" affordance (PRD #21): a confirmed, non-destructive move.
    func addRootToPlan() async {
        guard case .finished(.ready) = phase else { return }
        await moves.addToPlan(rootID)
        append(.assistant, L.string("breakdown_msg_added_to_plan"))
    }

    /// Leave at any point (PRD #22) — never trapped in a long interrogation.
    func bail() {
        guard !isFinished else { return }
        phase = .finished(.bailed)
    }

    // MARK: - Routing (deterministic, LLM-free)

    private func route(_ c: ImpedimentClassification, node: Node) async {
        switch c.kind {
        case .tooBig:
            let created = await capture(c.subtaskTitles, under: node)
            if created.isEmpty {
                append(.assistant, L.string("breakdown_msg_name_first_piece"))
                current = node
                phase = .asking
                return
            }
            append(.assistant, L.format("breakdown_msg_broke_into", listed(created.map(\.title))))
            stack.append(contentsOf: created.reversed()) // depth-first into the new parts
            advance()

        case .dontKnowHow, .scaredOfDoingItWrong:
            let title = (c.prerequisiteTitle?.trimmingCharacters(in: .whitespacesAndNewlines))
                .flatMap { $0.isEmpty ? nil : $0 } ?? defaultPrerequisite(for: c.kind)
            if let id = await moves.captureSubtask(under: node.id, title: title) {
                append(.assistant, L.format("breakdown_msg_added_prerequisite", title))
                stack.append(Node(id: id, title: title))
            }
            advance()

        case .persistentAvoidance:
            pendingDrop = node
            append(.assistant, L.format("breakdown_msg_confirm_drop", node.title))
            phase = .confirmingDrop

        case .transientObstacle:
            append(.assistant, L.string("breakdown_msg_transient_obstacle"))
            advance()

        case .somethingMoreUrgent:
            // Degraded until the priority model (#526) lands — record the tension, don't bury the item.
            append(.assistant, L.string("breakdown_msg_more_urgent"))
            advance()

        case .waitingOnDependency:
            // Degraded until set_blocked_by lands — acknowledge, don't fake an edge.
            append(.assistant, L.string("breakdown_msg_waiting_dependency"))
            advance()

        case .nothingStopping:
            append(.assistant, L.format("breakdown_msg_ready_to_go", node.title))
            advance()
        }
    }

    private func capture(_ titles: [String], under parent: Node) async -> [Node] {
        var created: [Node] = []
        for raw in titles {
            let title = raw.trimmingCharacters(in: .whitespacesAndNewlines)
            guard !title.isEmpty else { continue }
            if let id = await moves.captureSubtask(under: parent.id, title: title) {
                created.append(Node(id: id, title: title))
            }
        }
        return created
    }

    // MARK: - Worklist

    private func ask(_ node: Node) {
        current = node
        append(.assistant, L.format("breakdown_msg_whats_stopping", node.title))
        phase = .asking
    }

    private func advance() {
        if let next = stack.popLast() {
            ask(next)
        } else {
            current = nil
            let outcome: Outcome = rootDropped ? .dropped : .ready
            append(.assistant, L.string(outcome == .dropped
                ? "breakdown_msg_finished_dropped"
                : "breakdown_msg_finished_ready"))
            phase = .finished(outcome)
        }
    }

    // MARK: - Helpers

    private var isFinished: Bool { if case .finished = phase { return true }; return false }

    private func context(for node: Node) -> ItemContext {
        ItemContext(id: node.id, title: node.title, notes: node.id == rootID ? rootNotes : nil)
    }

    private func append(_ role: BreakdownMessage.Role, _ text: String) {
        messages.append(BreakdownMessage(id: makeID(), role: role, text: text))
    }

    private func listed(_ titles: [String]) -> String {
        titles.map { "“\($0)”" }.joined(separator: ", ")
    }

    private func defaultPrerequisite(for kind: ImpedimentClass) -> String {
        switch kind {
        case .scaredOfDoingItWrong: return L.string("breakdown_prereq_define_done")
        default: return L.string("breakdown_prereq_figure_out")
        }
    }
}

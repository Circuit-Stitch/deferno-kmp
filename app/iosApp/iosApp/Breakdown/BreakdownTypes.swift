import Foundation

/// The Breakdown AI core (Deferno#525). These are **plain Swift** types — no FoundationModels — so the
/// deterministic state machine and its tests never depend on Apple Intelligence. The on-device classifier
/// (`FoundationModelsClassifier`) maps the model's `@Generable` mirror onto [ImpedimentClassification];
/// everything downstream (routing, recursion, the Ready terminal) is LLM-free and unit-testable.

/// The eight honest reasons a task is stuck (PRD's impediment classes). The model interprets the
/// person's free-text answer into exactly one of these — it never picks the *move*; the engine does.
enum ImpedimentClass: String, CaseIterable, Sendable {
    case tooBig
    case waitingOnDependency
    case dontKnowHow
    case scaredOfDoingItWrong
    case somethingMoreUrgent
    case transientObstacle
    case persistentAvoidance
    case nothingStopping
}

/// A classified answer plus the args the chosen move needs. Args are best-effort: the engine validates
/// and falls back (e.g. an empty `subtaskTitles` re-asks rather than creating nothing).
struct ImpedimentClassification: Sendable, Equatable {
    let kind: ImpedimentClass
    /// `tooBig` → the smaller concrete parts to capture as child subtasks.
    let subtaskTitles: [String]
    /// `dontKnowHow` / `scaredOfDoingItWrong` → a scoped, finishable prerequisite to spin off.
    let prerequisiteTitle: String?

    init(kind: ImpedimentClass, subtaskTitles: [String] = [], prerequisiteTitle: String? = nil) {
        self.kind = kind
        self.subtaskTitles = subtaskTitles
        self.prerequisiteTitle = prerequisiteTitle
    }
}

/// The item under the lens — the classifier's *item context*. Title is always present; notes (the body)
/// only for the root the person opened (new children have none yet).
struct ItemContext: Sendable, Equatable {
    let id: String
    let title: String
    let notes: String?

    init(id: String, title: String, notes: String? = nil) {
        self.id = id
        self.title = title
        self.notes = notes
    }
}

/// The one model boundary (PRD #32): interpret the person's free-text answer into an impediment class +
/// args. The real impl runs on-device via Foundation Models; the test impl returns scripted classes.
protocol ImpedimentClassifier: Sendable {
    func classify(answer: String, item: ItemContext) async throws -> ImpedimentClassification
}

/// The structural moves the engine applies, as **native Swift** async calls. A thin adapter conforms the
/// shared Kotlin `BreakdownActions` to this, so every move rides the offline-first outbox (#185) — the
/// engine never reimplements persistence. v1 covers the moves with a client write path; the blocked ones
/// (`set_blocked_by`, priority) arrive as new methods when their Commands land.
protocol BreakdownMoves: Sendable {
    /// Capture a child subtask under [parentID]; returns the new item id (so the engine can recurse into
    /// it), or `nil` if nothing was captured (blank title).
    func captureSubtask(under parentID: String, title: String) async -> String?
    /// Drop [id] — the recoverable "let it go" terminal status, never a hard delete.
    func drop(_ id: String) async
    /// Add [id] to **today's** plan — the Ready terminal move.
    func addToPlan(_ id: String) async
}

/// One line in the Breakdown conversation the SwiftUI view renders.
struct BreakdownMessage: Identifiable, Sendable, Equatable {
    enum Role: Sendable { case assistant, user }
    let id: UUID
    let role: Role
    let text: String
}

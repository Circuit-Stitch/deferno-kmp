import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// The on-device [ImpedimentClassifier] over Apple Intelligence's **Foundation Models** (#525,
/// ADR-0027/0037). Uses native **guided generation**: the model fills an `@Generable` struct, so its reply
/// is structurally constrained to the impediment schema — Apple notes constrained decoding both removes
/// parse failures and *improves classification accuracy*. The person's answer never leaves the device.
///
/// The macOS twin of `MacInference.swift`'s brain-dump path: not `@available`-gated itself (it's constructed
/// on any macOS), but `classify` guards on the macOS-26 API + `SystemLanguageModel` availability, and
/// FoundationModels is weak-linked (`-weak_framework FoundationModels` in `project.yml`) because the app
/// deploys to 14.0. On an older Mac — or one without Apple Intelligence enabled — it throws
/// [BreakdownClassifierError.unavailable] and the View shows the unavailable state (no silent run).
/// Failures surface only a **non-PII** reason, never the answer or the model output (ADR-0027).
///
/// Ported verbatim from iosApp in #368 G10 — and it needed **no `@available` edits at all**: every
/// declaration here was already written dual-platform (`iOS 26.0, macOS 26.0`), and the file names no
/// UIKit and no Kotlin type. That it compiles unchanged on macOS is the whole reason the Swift-engine
/// route was chosen over driving the shared Kotlin engine (see `BreakdownEngine.swift`'s header).
struct FoundationModelsClassifier: ImpedimentClassifier {

    func classify(answer: String, item: ItemContext) async throws -> ImpedimentClassification {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            guard AppleIntelligence.isAvailable else { throw BreakdownClassifierError.unavailable }
            let session = LanguageModelSession(instructions: Self.instructions)
            do {
                let response = try await session.respond(to: Self.prompt(answer: answer, item: item),
                                                         generating: GenBreakdown.self)
                return response.content.toClassification()
            } catch {
                throw BreakdownClassifierError.generationFailed
            }
        }
        #endif
        throw BreakdownClassifierError.unavailable
    }

    /// Steer the model to *classify only* — it picks the impediment + args; the engine owns the move.
    private static let instructions = """
    You help someone get unstuck on a single to-do. They tell you, in their own words, WHY they haven't \
    done one task. Classify the real reason into exactly one impediment and extract only the arguments \
    that impediment needs. Prefer the most specific honest reason. Do not invent tasks, give pep talks, \
    or try to do the work — only classify.
    """

    private static func prompt(answer: String, item: ItemContext) -> String {
        var ctx = "Task: \"\(item.title)\""
        if let notes = item.notes, !notes.isEmpty { ctx += "\nDetails: \(notes)" }
        return "\(ctx)\n\nTheir answer to “what's stopping you?”: \"\(answer)\""
    }
}

/// Non-PII failure reasons (never carries the answer or model output, ADR-0027).
enum BreakdownClassifierError: Error { case unavailable, generationFailed }

#if canImport(FoundationModels)

/// The model-facing mirror of [ImpedimentClass] — `@Generable` so guided generation constrains the reply
/// to these eight options. Kept private to this file; `toKind()` maps it back to the plain engine type so
/// nothing downstream depends on FoundationModels or macOS 26.
@available(iOS 26.0, macOS 26.0, *)
@Generable
enum GenImpediment: String {
    case tooBig
    case waitingOnDependency
    case dontKnowHow
    case scaredOfDoingItWrong
    case somethingMoreUrgent
    case transientObstacle
    case persistentAvoidance
    case nothingStopping

    /// One-for-one mirror of [ImpedimentClass]: identical cases, so the shared `String` rawValue bridges
    /// them — no hand-maintained switch to drift out of sync. A case that ever lacks a downstream twin
    /// degrades to the benign "ready" no-op, never a structural move.
    func toKind() -> ImpedimentClass { ImpedimentClass(rawValue: rawValue) ?? .nothingStopping }
}

/// The `@Generable` classification the model fills. Optional args are modeled as an empty list / empty
/// string (guided generation favors total schemas), then normalized in [toClassification].
@available(iOS 26.0, macOS 26.0, *)
@Generable
struct GenBreakdown {
    @Guide(description: "The single best-matching reason the person is stuck.")
    let impediment: GenImpediment

    @Guide(description: "Only if the task is too big: 2–5 smaller, concrete next-action parts to split it into. Otherwise an empty list.")
    let subtaskTitles: [String]

    @Guide(description: "Only if they don't know how, or fear doing it wrong: one scoped, finishable prerequisite (research it, define 'done', or find someone to do it). Otherwise an empty string.")
    let prerequisiteTitle: String

    func toClassification() -> ImpedimentClassification {
        ImpedimentClassification(
            kind: impediment.toKind(),
            subtaskTitles: subtaskTitles,
            prerequisiteTitle: prerequisiteTitle.isEmpty ? nil : prerequisiteTitle
        )
    }
}

#endif

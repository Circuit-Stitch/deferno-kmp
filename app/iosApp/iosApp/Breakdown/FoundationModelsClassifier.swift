import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// The on-device [ImpedimentClassifier] over Apple Intelligence's **Foundation Models** (#525,
/// ADR-0027/0037). Uses native **guided generation**: the model fills an `@Generable` struct, so its reply
/// is structurally constrained to the impediment schema — Apple notes constrained decoding both removes
/// parse failures and *improves classification accuracy*. The person's answer never leaves the device.
///
/// The iOS twin of `IosInference.swift`'s brain-dump path: not `@available`-gated itself (it's constructed
/// on any iOS), but `classify` guards on the iOS-26 API + `SystemLanguageModel` availability and weak-links
/// FoundationModels (the app deploys to 16). On an older device — or one without Apple Intelligence enabled —
/// it throws [BreakdownClassifierError.unavailable] and the View shows the unavailable state (no silent run).
/// Failures surface only a **non-PII** reason, never the answer or the model output (ADR-0027).
struct FoundationModelsClassifier: ImpedimentClassifier {

    func classify(answer: String, item: ItemContext) async throws -> ImpedimentClassification {
        #if canImport(FoundationModels)
        if #available(iOS 26.0, macOS 26.0, *) {
            guard case .available = SystemLanguageModel.default.availability else {
                throw BreakdownClassifierError.unavailable
            }
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
/// nothing downstream depends on FoundationModels or iOS 26.
@available(iOS 26.0, macOS 26.0, *)
@Generable
enum GenImpediment {
    case tooBig
    case waitingOnDependency
    case dontKnowHow
    case scaredOfDoingItWrong
    case somethingMoreUrgent
    case transientObstacle
    case persistentAvoidance
    case nothingStopping
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

@available(iOS 26.0, macOS 26.0, *)
extension GenImpediment {
    func toKind() -> ImpedimentClass {
        switch self {
        case .tooBig: return .tooBig
        case .waitingOnDependency: return .waitingOnDependency
        case .dontKnowHow: return .dontKnowHow
        case .scaredOfDoingItWrong: return .scaredOfDoingItWrong
        case .somethingMoreUrgent: return .somethingMoreUrgent
        case .transientObstacle: return .transientObstacle
        case .persistentAvoidance: return .persistentAvoidance
        case .nothingStopping: return .nothingStopping
        }
    }
}

#endif

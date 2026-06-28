import Deferno
import Foundation
#if canImport(FoundationModels)
import FoundationModels
#endif

/// The one canonical "can on-device Apple Intelligence actually run here?" gate — the FoundationModels
/// weak-link (iOS 26+, app deploys to 16), the `#available` guard, and `SystemLanguageModel` availability
/// in a single place. The inference seam, the Breakdown classifier, and its View all read this, so there's
/// one answer to keep correct, not three (#525).
enum AppleIntelligence {
    static var isAvailable: Bool {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            if case .available = SystemLanguageModel.default.availability { return true }
        }
        #endif
        return false
    }
}

/// In-process inference (#269, ADR-0037): implements the shared Kotlin `NativeInference` port over Apple
/// Intelligence's **Foundation Models** (`LanguageModelSession`). The model runs fully on-device, so the
/// person's transcript never leaves the phone (ADR-0009/0027). Kotlin owns the schema + validation; this just
/// returns the model's JSON text (or a non-PII error category), gated on model availability. The iOS twin of
/// macOS `MacInference`.
///
/// FoundationModels is iOS 26+; the app deploys to 16.0, so every use is `@available`-guarded and the
/// framework is weak-linked (`-weak_framework FoundationModels` in the Xcode project). On an older iPhone — or
/// one where Apple Intelligence isn't enabled/downloaded — `isAvailable()` is `false` and the seam answers
/// `NotConfigured`, so nothing ever runs silently (the Brain dump salvages instead).
final class IosInference: NativeInference {

    func isAvailable() -> Bool { AppleIntelligence.isAvailable }

    func infer(
        instructions: String,
        content: String,
        schemaName: String,
        schemaShape: String,
        onJson: @escaping (String) -> Void,
        onError: @escaping (String) -> Void
    ) {
        #if canImport(FoundationModels)
        if #available(iOS 26, *) {
            let model = SystemLanguageModel.default
            guard case .available = model.availability else { onError("unavailable"); return }
            // No `@Generable` Swift type — the schema is a *runtime* contract Kotlin owns. So steer the model
            // with Kotlin's by-example shape (exact keys + types) and let Kotlin decode/validate the reply.
            // The shape closes the gap a name-only prompt leaves on a small on-device model.
            let steered = """
            \(instructions)

            Respond with ONLY a single minified JSON object matching this exact shape (the same keys and \
            value types; "?" marks an optional field that may be omitted or null):
            \(schemaShape)
            No markdown code fences, no commentary, no text before or after the JSON.
            """
            let session = LanguageModelSession(instructions: steered)
            // `_Concurrency.Task` — bare `Task` resolves to the exported Kotlin `Deferno.Task` domain model.
            _Concurrency.Task {
                do {
                    let response = try await session.respond(to: content)
                    // Always hop to main: the seam's coroutine resumes here.
                    await MainActor.run { onJson(response.content) }
                } catch {
                    await MainActor.run { onError(Self.category(of: error)) }
                }
            }
            return
        }
        #endif
        onError("unavailable")
    }

    /// A **non-PII** category for a generation failure — never the model output (ADR-0027). Kept coarse on
    /// purpose: it's a developer diagnostic, and the exact `GenerationError` case spellings aren't worth
    /// coupling to.
    private static func category(of error: Error) -> String {
        #if canImport(FoundationModels)
        if #available(iOS 26, *), error is LanguageModelSession.GenerationError {
            return "generation-failed"
        }
        #endif
        return "inference-error"
    }
}

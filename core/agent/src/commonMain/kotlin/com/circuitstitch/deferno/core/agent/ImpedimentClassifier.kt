package com.circuitstitch.deferno.core.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The eight honest reasons a single task is stuck (Deferno#525) — the impediment classes the on-device
 * classifier interprets a person's free-text answer into. The model picks exactly one; it never picks the
 * *move* the Breakdown engine then routes to (the engine owns that, LLM-free). Mirrors the iOS
 * `ImpedimentClass`; the constant names are the wire values guided generation constrains the reply to.
 */
@Serializable
enum class ImpedimentClass {
    tooBig,
    waitingOnDependency,
    dontKnowHow,
    scaredOfDoingItWrong,
    somethingMoreUrgent,
    transientObstacle,
    persistentAvoidance,
    nothingStopping,
}

/**
 * A classified answer plus the args the chosen move needs (Deferno#525). Args are best-effort — the
 * Breakdown engine validates and falls back (an empty [subtaskTitles] re-asks rather than creating nothing).
 */
data class ImpedimentClassification(
    val kind: ImpedimentClass,
    /** `tooBig` → the smaller concrete parts to capture as child subtasks. */
    val subtaskTitles: List<String> = emptyList(),
    /** `dontKnowHow` / `scaredOfDoingItWrong` → a scoped, finishable prerequisite to spin off. */
    val prerequisiteTitle: String? = null,
)

/**
 * The one model boundary of the Breakdown flow (Deferno#525): interpret a person's free-text answer to
 * "what's stopping you?" into an [ImpedimentClassification]. The Breakdown engine consults this at exactly
 * one point; move selection, recursion, and the terminal check are deterministic and never call it. The
 * production impl ([InferenceImpedimentClassifier]) runs on-device via the [InferenceEngine]; tests pass a
 * scripted classifier so the engine runs with no model.
 */
interface ImpedimentClassifier {
    /**
     * Classify [answer] for the task ([taskTitle] + optional [taskNotes] are the model's item context).
     * Throws [BreakdownClassifierException] on any inference failure — the engine catches it and lets the
     * person rephrase (availability is gated up front, so this is a generation hiccup, not "no engine").
     */
    suspend fun classify(answer: String, taskTitle: String, taskNotes: String?): ImpedimentClassification
}

/** A non-PII inference failure (never carries the answer or model output — the [InferenceEngine] privacy invariant). */
class BreakdownClassifierException(detail: String) : Exception(detail)

/**
 * The on-device [ImpedimentClassifier] over the Agent's [InferenceEngine] (Deferno#525, ADR-0027): the
 * Android/desktop twin of iOS's Foundation Models classifier. It mirrors [Extractor] — a `@Serializable`
 * result schema fills [InferenceSchema] so the engine returns a *validated* classification (guided
 * generation on a capable engine), and every failure surfaces as a typed [BreakdownClassifierException].
 *
 * Which engine actually answers is whichever is selected (the cloud relay when entitled, or a future
 * general-purpose on-device LLM); the brain-dump-only deterministic floor can't classify, so the Breakdown
 * surface gates entry on [hasGeneralPurposeEngine] before it ever builds this.
 */
class InferenceImpedimentClassifier(
    private val inference: InferenceEngine,
) : ImpedimentClassifier {

    override suspend fun classify(
        answer: String,
        taskTitle: String,
        taskNotes: String?,
    ): ImpedimentClassification {
        val result = inference.infer(
            InferenceRequest(
                instructions = INSTRUCTIONS,
                content = prompt(answer, taskTitle, taskNotes),
                schema = InferenceSchema(Schema.serializer()),
            ),
        )
        return when (result) {
            is InferenceResult.Success -> result.value.toClassification()
            is InferenceResult.Failure -> throw BreakdownClassifierException(result.detail)
        }
    }

    /**
     * The model-facing schema (guided generation constrains the reply to it); normalized in
     * [toClassification]. `internal` (not private) only so the round-tripping [FakeInferenceEngine] in
     * commonTest can enqueue a canned reply against it.
     */
    @Serializable
    @SerialName("impediment_classification")
    internal data class Schema(
        val impediment: ImpedimentClass = ImpedimentClass.nothingStopping,
        val subtaskTitles: List<String> = emptyList(),
        val prerequisiteTitle: String = "",
    ) {
        fun toClassification(): ImpedimentClassification = ImpedimentClassification(
            kind = impediment,
            subtaskTitles = subtaskTitles,
            prerequisiteTitle = prerequisiteTitle.ifEmpty { null },
        )
    }

    private fun prompt(answer: String, taskTitle: String, taskNotes: String?): String = buildString {
        append("Task: \"").append(taskTitle).append('"')
        if (!taskNotes.isNullOrEmpty()) append("\nDetails: ").append(taskNotes)
        append("\n\nTheir answer to \"what's stopping you?\": \"").append(answer).append('"')
    }

    private companion object {
        val INSTRUCTIONS = """
            You help someone get unstuck on a single to-do. They tell you, in their own words, WHY they
            haven't done one task. Classify the real reason into exactly one impediment and extract only the
            arguments that impediment needs. Prefer the most specific honest reason. Do not invent tasks,
            give pep talks, or try to do the work - only classify. Return only impediment_classification JSON.

            impediment is exactly one of:
            - tooBig: too large or vague to start; set subtaskTitles to 2-5 smaller, concrete next-action parts.
            - dontKnowHow: they don't know how; set prerequisiteTitle to one scoped step to learn or figure it out.
            - scaredOfDoingItWrong: afraid of doing it wrong / high-stakes; set prerequisiteTitle to one step to
              define what 'done' looks like or decide who should do it.
            - waitingOnDependency: blocked waiting on someone or something else.
            - somethingMoreUrgent: something more important is taking priority right now.
            - transientObstacle: a temporary, passing obstacle (timing, weather, mood).
            - persistentAvoidance: they keep avoiding it and may not actually need it.
            - nothingStopping: nothing real is stopping them; it is ready to start.

            Set subtaskTitles only for tooBig (otherwise an empty list). Set prerequisiteTitle only for
            dontKnowHow or scaredOfDoingItWrong (otherwise an empty string).
        """.trimIndent()
    }
}

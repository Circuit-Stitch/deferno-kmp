package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline
import kotlin.time.Instant

/**
 * Propose-only Brain dump extractor: Transcript plus caller-injected date context in, draft Tasks out.
 */
class Extractor(
    private val inference: InferenceEngine,
    private val referenceLookup: ReferenceLookup = ReferenceLookup.Empty,
) {
    suspend fun extract(input: ExtractorInput): InferenceResult<DraftTaskProposal> {
        val transcript = input.transcript.text.trim()
        if (transcript.isBlank()) {
            return InferenceResult.Success(DraftTaskProposal())
        }

        val anchors = referenceLookup.searchItems(transcript)
        val result = inference.infer(
            InferenceRequest(
                instructions = EXTRACTOR_INSTRUCTIONS,
                content = buildContent(input, anchors),
                schema = InferenceSchema(DraftTasks.serializer()),
            ),
        )
        return when (result) {
            is InferenceResult.Failure -> result
            is InferenceResult.Success -> InferenceResult.Success(result.value.toProposal(anchors))
        }
    }

    suspend fun extract(
        transcript: Transcript,
        today: LocalDate,
        timeZone: String,
    ): InferenceResult<DraftTaskProposal> = extract(ExtractorInput(transcript, today, timeZone))

    private fun buildContent(input: ExtractorInput, anchors: List<ItemAnchor>): String = buildString {
        appendLine("dateContext():")
        appendLine("- today=${input.today}")
        appendLine("- timezone=${input.timeZone}")
        appendLine("transcript:")
        appendLine(input.transcript.text.trim())
        appendLine()
        appendLine("searchItems(query) local anchors:")
        if (anchors.isEmpty()) {
            appendLine("- none")
        } else {
            anchors.forEach { appendLine("- ${it.promptLine()}") }
        }
    }
}

data class ExtractorInput(
    val transcript: Transcript,
    val today: LocalDate,
    val timeZone: String,
)

@JvmInline
value class Transcript(val text: String)

data class DraftTaskProposal(
    val drafts: List<DraftTask> = emptyList(),
    val warnings: List<ProposalWarning> = emptyList(),
)

data class ProposalWarning(
    val kind: ProposalWarningKind,
    val draftId: String? = null,
    val field: String? = null,
    val target: String? = null,
)

enum class ProposalWarningKind {
    InvalidDraft,
    InvalidScore,
    DanglingDraftRef,
    UnresolvedExistingRef,
}

@Serializable
@SerialName("draft_tasks")
data class DraftTasks(
    val drafts: List<DraftTask> = emptyList(),
)

@Serializable
data class DraftTask(
    val id: String,
    val title: String,
    val description: String? = null,
    val completeBy: LocalDate? = null,
    val deadlineTimeOfDay: LocalTime? = null,
    val desire: Double? = null,
    val productive: Double? = null,
    val parentId: String? = null,
    val children: List<String> = emptyList(),
    val nextTaskId: String? = null,
)

interface ReferenceLookup {
    suspend fun searchItems(query: String): List<ItemAnchor>

    object Empty : ReferenceLookup {
        override suspend fun searchItems(query: String): List<ItemAnchor> = emptyList()
    }
}

data class ItemAnchor(
    val ref: String,
    val kind: ItemKind,
    val title: String,
    val orgSlug: String,
    val parentId: String? = null,
    val completeBy: LocalDate? = null,
    val dateCreated: Instant? = null,
    val description: String? = null,
    val deadlineTimeOfDay: LocalTime? = null,
) {
    init {
        require(ref.isNotBlank()) { "ItemAnchor.ref must not be blank" }
        require(title.isNotBlank()) { "ItemAnchor.title must not be blank" }
        require(orgSlug.isNotBlank()) { "ItemAnchor.orgSlug must not be blank" }
    }

    fun promptLine(): String = listOfNotNull(
        "ref=$ref",
        "kind=$kind",
        "title=$title",
        "orgSlug=$orgSlug",
        parentId?.let { "parentId=$it" },
        completeBy?.let { "completeBy=$it" },
        deadlineTimeOfDay?.let { "deadlineTimeOfDay=$it" },
        dateCreated?.let { "dateCreated=$it" },
        description?.takeIf { it.isNotBlank() }?.let { "description=$it" },
    ).joinToString(", ")
}

private fun DraftTasks.toProposal(anchors: List<ItemAnchor>): DraftTaskProposal {
    val warnings = mutableListOf<ProposalWarning>()
    val draftIds = drafts.mapNotNull { draft ->
        draft.id.trim().takeIf { it.isNotEmpty() && draft.title.isNotBlank() }
    }.toSet()
    val anchorRefs = anchors.map { it.ref }.toSet()
    val cleanDrafts = drafts.mapNotNull { draft ->
        val id = draft.id.trim()
        val title = draft.title.trim()
        if (id.isBlank() || title.isBlank()) {
            warnings += ProposalWarning(ProposalWarningKind.InvalidDraft, id.ifBlank { null })
            return@mapNotNull null
        }
        draft.copy(
            id = id,
            title = title,
            description = draft.description?.trim()?.takeIf { it.isNotEmpty() },
            parentId = draft.parentId.cleanRelation(id, "parentId", draftIds, anchorRefs, warnings),
            children = draft.children.mapNotNull {
                it.cleanRelation(id, "children", draftIds, anchorRefs, warnings)
            },
            nextTaskId = draft.nextTaskId.cleanRelation(id, "nextTaskId", draftIds, anchorRefs, warnings),
            desire = draft.desire.cleanScore(id, "desire", warnings),
            productive = draft.productive.cleanScore(id, "productive", warnings),
        )
    }
    return DraftTaskProposal(cleanDrafts, warnings)
}

private fun String?.cleanRelation(
    draftId: String,
    field: String,
    draftIds: Set<String>,
    anchorRefs: Set<String>,
    warnings: MutableList<ProposalWarning>,
): String? {
    val target = this?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (target in draftIds || target in anchorRefs) return target
    val kind = if (target.looksLikeItemRef()) {
        ProposalWarningKind.UnresolvedExistingRef
    } else {
        ProposalWarningKind.DanglingDraftRef
    }
    warnings += ProposalWarning(kind, draftId, field, target)
    return if (kind == ProposalWarningKind.UnresolvedExistingRef) target else null
}

private fun Double?.cleanScore(
    draftId: String,
    field: String,
    warnings: MutableList<ProposalWarning>,
): Double? {
    if (this == null) return null
    if (this in 0.0..1.0) return this
    warnings += ProposalWarning(ProposalWarningKind.InvalidScore, draftId, field, toString())
    return null
}

private fun String.looksLikeItemRef(): Boolean =
    '-' in this && substringAfterLast('-').let { it.isNotEmpty() && it.all(Char::isDigit) }

private const val EXTRACTOR_INSTRUCTIONS = """
Extract draft Tasks from the Brain dump transcript.
Return only draft_tasks JSON. Use empty drafts for empty or garbled input.
Fields: id, title, description, completeBy, deadlineTimeOfDay, desire, productive, parentId, children, nextTaskId.
Use completeBy as an ISO yyyy-mm-dd date and deadlineTimeOfDay as HH:MM.
Resolve relative dates from dateContext().
Use desire and productive scores from 0.0 to 1.0.
Use parentId, children, and nextTaskId only for draft ids in this response or existing Item refs from searchItems(query).
Reference existing Items by ref only, never UUID.
Blockers are represented as elder siblings in the Task tree: within a parent's ordered children list,
a Task is blocked by any sibling whose index is higher than that Task's index.
Dependencies are represented as children.
Do not emit priority, or any write command.
"""

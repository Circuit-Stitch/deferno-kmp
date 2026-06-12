package com.circuitstitch.deferno.core.agent

import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The #147 acceptance round-trip: a kotlinx-serializable schema travels through the
 * [InferenceEngine] seam via [FakeInferenceEngine] — typed request in, validated instance out —
 * and every canned failure mode surfaces typed, never thrown.
 */
class FakeInferenceEngineTest {

    private val schema = InferenceSchema(DraftTasks.serializer())

    private fun request() = InferenceRequest(
        instructions = "Extract draft tasks from the transcript.",
        content = "mow the lawn, then sharpen the mower blades by Friday",
        schema = schema,
    )

    @Test
    fun roundTripsASerializableSchemaToAValidatedInstance() = runTest {
        val engine = FakeInferenceEngine()
        val canned = DraftTasks(
            drafts = listOf(
                DraftTask(id = "mow", title = "Mow the lawn", productive = 1.0),
                DraftTask(id = "sharpen", title = "Sharpen the mower blades", completeBy = LocalDate(2026, 6, 12)),
            ),
        )
        engine.enqueue(canned)

        val result = engine.infer(request())

        // Value equality across the encode→decode trip is the round-trip proof: what the seam
        // returns is what the schema serializes to and parses back from.
        assertEquals(canned, assertIs<InferenceResult.Success<DraftTasks>>(result).value)
    }

    @Test
    fun surfacesCannedFailuresTypedRatherThanThrown() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(InferenceResult.Failure.MalformedOutput("schema validation failed"))

        val result = engine.infer(request())

        assertEquals(
            InferenceResult.Failure.MalformedOutput("schema validation failed"),
            result,
        )
    }

    @Test
    fun reportsNotConfiguredWhenNothingIsEnqueued() = runTest {
        val result = FakeInferenceEngine().infer(request())

        assertIs<InferenceResult.Failure.NotConfigured>(result)
    }

    @Test
    fun recordsTheRequestsItServes() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(DraftTasks(drafts = emptyList()))

        engine.infer(request())

        val seen = engine.requests.single()
        assertEquals("Extract draft tasks from the transcript.", seen.instructions)
        assertEquals("draft_tasks", seen.schema.name)
    }

    @Test
    fun extractorExtractsMultiTaskDraftsWithInjectedDateContext() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(
            DraftTasks(
                drafts = listOf(
                    DraftTask(id = "mow", title = "Mow the lawn", productive = 0.8),
                    DraftTask(
                        id = "sharpen",
                        title = "Sharpen the mower blades",
                        completeBy = LocalDate(2026, 6, 12),
                        desire = 0.3,
                        productive = 0.6,
                    ),
                ),
            ),
        )

        val proposal = success(
            Extractor(engine).extract(
                transcript = Transcript("mow the lawn, then sharpen the blades by Friday"),
                today = LocalDate(2026, 6, 8),
                timeZone = "America/Los_Angeles",
            ),
        )

        assertEquals(2, proposal.drafts.size)
        assertEquals(LocalDate(2026, 6, 12), proposal.drafts[1].completeBy)
        assertEquals(0.3, proposal.drafts[1].desire)
        assertEquals(0.6, proposal.drafts[1].productive)
        val request = engine.requests.single()
        assertTrue("dateContext():" in request.content)
        assertTrue("- today=2026-06-08" in request.content)
        assertTrue("- timezone=America/Los_Angeles" in request.content)
    }

    @Test
    fun extractorPreservesDecompositionTreesAndSequenceChains() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(
            DraftTasks(
                drafts = listOf(
                    DraftTask(id = "root", title = "Ship extractor", children = listOf("parse", "review")),
                    DraftTask(id = "parse", title = "Parse transcript", parentId = "root", nextTaskId = "review"),
                    DraftTask(id = "review", title = "Review drafts", parentId = "root"),
                ),
            ),
        )

        val proposal = success(Extractor(engine).extract(sampleInput("ship extractor")))

        assertEquals(listOf("parse", "review"), proposal.drafts[0].children)
        assertEquals("root", proposal.drafts[1].parentId)
        assertEquals("review", proposal.drafts[1].nextTaskId)
        assertTrue(proposal.warnings.isEmpty())
    }

    @Test
    fun extractorPassesLocalSearchItemAnchorsAndAllowsReturnedRefs() = runTest {
        val engine = FakeInferenceEngine()
        val lookup = RecordingLookup(
            listOf(
                ItemAnchor(
                    ref = "u-e4h2qk-42",
                    kind = ItemKind.Task,
                    title = "Prepare launch checklist",
                    orgSlug = "u-e4h2qk",
                    parentId = "u-e4h2qk-10",
                    completeBy = LocalDate(2026, 6, 11),
                    dateCreated = Instant.parse("2026-06-01T12:00:00Z"),
                    description = "Existing checklist",
                    deadlineTimeOfDay = LocalTime(9, 30),
                ),
            ),
        )
        engine.enqueue(
            DraftTasks(
                drafts = listOf(
                    DraftTask(id = "followup", title = "Finish checklist", parentId = "u-e4h2qk-42"),
                ),
            ),
        )

        val proposal = success(Extractor(engine, lookup).extract(sampleInput("finish the launch checklist")))

        assertEquals(listOf("finish the launch checklist"), lookup.queries)
        assertEquals("u-e4h2qk-42", proposal.drafts.single().parentId)
        assertTrue(proposal.warnings.isEmpty())
        val content = engine.requests.single().content
        listOf(
            "ref=u-e4h2qk-42",
            "kind=Task",
            "title=Prepare launch checklist",
            "orgSlug=u-e4h2qk",
            "parentId=u-e4h2qk-10",
            "completeBy=2026-06-11",
            "deadlineTimeOfDay=09:30",
            "dateCreated=2026-06-01T12:00:00Z",
            "description=Existing checklist",
        ).forEach { assertTrue(it in content, "missing anchor field: $it") }
    }

    @Test
    fun extractorRejectsDanglingDraftRefsBeforeReview() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(
            DraftTasks(
                drafts = listOf(
                    DraftTask(
                        id = "parent",
                        title = "Parent",
                        children = listOf("missing-child"),
                        nextTaskId = "missing-next",
                    ),
                ),
            ),
        )

        val proposal = success(Extractor(engine).extract(sampleInput("make a broken tree")))

        assertEquals(emptyList(), proposal.drafts.single().children)
        assertNull(proposal.drafts.single().nextTaskId)
        assertEquals(
            listOf(ProposalWarningKind.DanglingDraftRef, ProposalWarningKind.DanglingDraftRef),
            proposal.warnings.map { it.kind },
        )
    }

    @Test
    fun extractorWarnsAndPreservesUnresolvedExistingRefs() = runTest {
        val engine = FakeInferenceEngine()
        engine.enqueue(
            DraftTasks(
                drafts = listOf(
                    DraftTask(id = "followup", title = "Follow up", parentId = "u-e4h2qk-404"),
                ),
            ),
        )

        val proposal = success(Extractor(engine).extract(sampleInput("follow up on missing ref")))

        assertEquals("u-e4h2qk-404", proposal.drafts.single().parentId)
        assertEquals(ProposalWarningKind.UnresolvedExistingRef, proposal.warnings.single().kind)
    }

    @Test
    fun extractorDegradesEmptyOrGarbledTranscriptsWithoutErrors() = runTest {
        val emptyEngine = FakeInferenceEngine()
        val empty = success(Extractor(emptyEngine).extract(sampleInput("   ")))
        assertTrue(empty.drafts.isEmpty())
        assertTrue(emptyEngine.requests.isEmpty())

        val garbledEngine = FakeInferenceEngine()
        garbledEngine.enqueue(
            DraftTasks(
                drafts = listOf(
                    DraftTask(id = "kept", title = "Keep partial draft"),
                    DraftTask(id = "dropped", title = ""),
                ),
            ),
        )

        val partial = success(Extractor(garbledEngine).extract(sampleInput("uhh ??? lawn maybe")))

        assertEquals(listOf("kept"), partial.drafts.map { it.id })
        assertEquals(ProposalWarningKind.InvalidDraft, partial.warnings.single().kind)
    }

    private fun sampleInput(text: String) = ExtractorInput(
        transcript = Transcript(text),
        today = LocalDate(2026, 6, 8),
        timeZone = "America/Los_Angeles",
    )

    private fun success(result: InferenceResult<DraftTaskProposal>): DraftTaskProposal =
        assertIs<InferenceResult.Success<DraftTaskProposal>>(result).value

    private class RecordingLookup(private val anchors: List<ItemAnchor>) : ReferenceLookup {
        val queries = mutableListOf<String>()

        override suspend fun searchItems(query: String): List<ItemAnchor> {
            queries += query
            return anchors
        }
    }
}

package com.circuitstitch.deferno.shell

import com.circuitstitch.deferno.core.agent.DraftTask
import com.circuitstitch.deferno.core.agent.DraftTaskProposal
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.agent.Transcript
import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.speech.SpeechAvailability
import com.circuitstitch.deferno.core.speech.SpeechError
import com.circuitstitch.deferno.core.speech.TranscriptEvent
import com.circuitstitch.deferno.core.speech.UnavailableReason
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The **Brain dump** state machine (ADR-0027, #150): availability gating, continuous accumulation of
 * dictated utterances, the stop → extract → review flow, propose-only accept through the create seam,
 * and the typed failure/permission states. Pure logic — no Compose, no device — driven by a
 * [FakeSpeechToText] + fake extract/create seams, on [UnconfinedTestDispatcher] so the launched
 * availability query, listen() collection, extract and create run eagerly.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BrainDumpComponentTest {

    private val today = LocalDate(2026, 6, 14)

    private fun TestScope.component(
        available: SpeechAvailability = SpeechAvailability.Available,
        events: MutableSharedFlow<TranscriptEvent> = MutableSharedFlow(extraBufferCapacity = 16),
        extract: suspend (Transcript) -> InferenceResult<DraftTaskProposal> =
            { InferenceResult.Success(DraftTaskProposal()) },
        create: suspend (CreateItem.Payload) -> CommandResult =
            { CommandResult.Accepted(CommandKind.CreateItem) },
        onDone: () -> Unit = {},
        scope: CoroutineScope = backgroundScope,
    ) = DefaultBrainDumpComponent(
        extract = extract,
        create = create,
        onDone = onDone,
        scope = scope,
        timeZone = "America/New_York",
        speech = FakeSpeechToText(available = available, events = events),
        locale = "en-US",
    )

    @Test
    fun micAvailable_reflectsEngineAvailability() = runTest(UnconfinedTestDispatcher()) {
        assertTrue(component(available = SpeechAvailability.Available).state.value.micAvailable)
        assertFalse(
            component(available = SpeechAvailability.Unavailable(UnavailableReason.ModelMissing)).state.value.micAvailable,
            "no mic when the engine isn't available (model missing / non-English locale)",
        )
    }

    @Test
    fun noEngine_isInertNotCrashing() = runTest(UnconfinedTestDispatcher()) {
        val c = DefaultBrainDumpComponent(
            extract = { InferenceResult.Success(DraftTaskProposal()) },
            create = { CommandResult.Accepted(CommandKind.CreateItem) },
            onDone = {},
            scope = backgroundScope,
        )
        assertFalse(c.state.value.micAvailable)
        c.startDictation() // a no-op without an engine — never crashes, never flips state
        assertEquals(Phase.Idle, c.state.value.phase)
    }

    @Test
    fun listening_accumulatesFinalsAcrossUtterances_partialIsLiveTail() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)

        c.startDictation()
        assertEquals(Phase.Listening, c.state.value.phase)

        events.emit(TranscriptEvent.Partial("buy"))
        assertEquals("buy", c.state.value.transcript)

        events.emit(TranscriptEvent.Final("buy milk"))
        assertEquals("buy milk", c.state.value.transcript, "the settled utterance replaces the partial")

        // A continuous session keeps going after a Final (the New form would have stopped here).
        assertEquals(Phase.Listening, c.state.value.phase)

        events.emit(TranscriptEvent.Partial("then"))
        assertEquals("buy milk then", c.state.value.transcript, "the next partial is the live tail after settled text")

        events.emit(TranscriptEvent.Final("then call mom"))
        assertEquals("buy milk then call mom", c.state.value.transcript, "finals accumulate, single-spaced")
    }

    @Test
    fun stop_blankTranscript_returnsToIdle_withoutExtracting() = runTest(UnconfinedTestDispatcher()) {
        var extractCalls = 0
        val c = component(extract = { extractCalls++; InferenceResult.Success(DraftTaskProposal()) })
        c.startDictation()
        c.stopDictation()
        assertEquals(Phase.Idle, c.state.value.phase)
        assertEquals(0, extractCalls, "nothing dictated ⇒ no extraction")
    }

    @Test
    fun stop_runsExtractor_andPresentsDrafts() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        var seen: Transcript? = null
        val c = component(
            events = events,
            extract = { t ->
                seen = t
                InferenceResult.Success(
                    DraftTaskProposal(
                        drafts = listOf(
                            DraftTask(id = "d1", title = "Buy milk", completeBy = LocalDate(2026, 6, 15)),
                            DraftTask(id = "d2", title = "Call mom"),
                        ),
                    ),
                )
            },
        )
        c.startDictation()
        events.emit(TranscriptEvent.Final("buy milk and call mom"))
        c.stopDictation()

        assertEquals(Transcript("buy milk and call mom"), seen, "the accumulated transcript is handed to the Extractor")
        assertEquals(Phase.Review, c.state.value.phase)
        assertEquals(listOf("Buy milk", "Call mom"), c.state.value.drafts.map { it.title })
        assertEquals("Due 2026-06-15", c.state.value.drafts.first().detail)
        assertTrue(c.state.value.drafts.all { it.status == DraftStatus.Pending })
    }

    @Test
    fun stop_emptyProposal_isAGentleReviewNotAnError() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events, extract = { InferenceResult.Success(DraftTaskProposal()) })
        c.startDictation()
        events.emit(TranscriptEvent.Final("um, nothing really"))
        c.stopDictation()
        assertEquals(Phase.Review, c.state.value.phase)
        assertTrue(c.state.value.drafts.isEmpty())
    }

    @Test
    fun stop_extractionFailure_surfacesTypedReason() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events, extract = { InferenceResult.Failure.NotConfigured("agent off") })
        c.startDictation()
        events.emit(TranscriptEvent.Final("plan my week"))
        c.stopDictation()
        assertEquals(Phase.Failed(FailureReason.NotConfigured), c.state.value.phase)
    }

    @Test
    fun review_flagsDroppedRelations_whenDraftsReferenceEachOther() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(
            events = events,
            extract = {
                InferenceResult.Success(
                    DraftTaskProposal(
                        drafts = listOf(
                            DraftTask(id = "p", title = "Trip"),
                            DraftTask(id = "c", title = "Book hotel", parentId = "p"),
                        ),
                    ),
                )
            },
        )
        c.startDictation()
        events.emit(TranscriptEvent.Final("plan a trip and book a hotel"))
        c.stopDictation()
        assertTrue(c.state.value.relationsDropped, "an inter-draft parent ref ⇒ the flat-create note shows")
    }

    @Test
    fun accept_createsThroughTheCommandSeam_andMarksCreated() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        var payload: CreateItem.Payload? = null
        val c = component(
            events = events,
            extract = {
                InferenceResult.Success(
                    DraftTaskProposal(
                        drafts = listOf(
                            DraftTask(
                                id = "d1",
                                title = "Buy milk",
                                completeBy = LocalDate(2026, 6, 15),
                                deadlineTimeOfDay = LocalTime(9, 30),
                                desire = 0.8,
                            ),
                        ),
                    ),
                )
            },
            create = { payload = it; CommandResult.Accepted(CommandKind.CreateItem) },
        )
        c.startDictation()
        events.emit(TranscriptEvent.Final("buy milk tomorrow morning"))
        c.stopDictation()

        c.acceptDraft("d1")
        assertEquals(DraftStatus.Created, c.state.value.drafts.first().status)

        val task = (payload as CreateItem.Payload.Task).payload
        assertEquals("Buy milk", task.title)
        assertEquals("09:30", task.deadlineTimeOfDay)
        assertEquals(0.8, task.desire)
        // 2026-06-15 start-of-day in America/New_York ⇒ 04:00Z (EDT, UTC-4) as an RFC3339 instant.
        assertEquals("2026-06-15T04:00:00Z", task.completeBy)
    }

    @Test
    fun accept_offline_marksOffline_notCreated() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(
            events = events,
            extract = { InferenceResult.Success(DraftTaskProposal(drafts = listOf(DraftTask(id = "d1", title = "X")))) },
            create = { CommandResult.Offline(CommandKind.CreateItem) },
        )
        c.startDictation()
        events.emit(TranscriptEvent.Final("x"))
        c.stopDictation()
        c.acceptDraft("d1")
        assertEquals(DraftStatus.Offline, c.state.value.drafts.first().status)
    }

    @Test
    fun dismissDraft_removesItFromReview() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(
            events = events,
            extract = {
                InferenceResult.Success(
                    DraftTaskProposal(drafts = listOf(DraftTask("d1", "Keep"), DraftTask("d2", "Drop"))),
                )
            },
        )
        c.startDictation()
        events.emit(TranscriptEvent.Final("two things"))
        c.stopDictation()

        c.dismissDraft("d2")
        assertEquals(listOf("Keep"), c.state.value.drafts.map { it.title })
    }

    @Test
    fun speechError_permissionDenied_landsPermanent() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)
        c.startDictation()
        // The engine settled a real denial (#120): terminal until flipped in OS settings.
        events.emit(TranscriptEvent.Error(SpeechError.PermissionDenied))
        assertEquals(Phase.PermissionPermanentlyDenied, c.state.value.phase)
    }

    @Test
    fun speechError_engine_isAGentleFailure() = runTest(UnconfinedTestDispatcher()) {
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)
        c.startDictation()
        events.emit(TranscriptEvent.Error(SpeechError.Engine))
        assertEquals(Phase.Failed(FailureReason.Speech), c.state.value.phase)
    }

    @Test
    fun permissionDenied_setsTheGentleStates() = runTest(UnconfinedTestDispatcher()) {
        val c = component()
        c.dictationPermissionDenied(permanentlyDenied = false)
        assertEquals(Phase.PermissionDenied, c.state.value.phase)
        c.dictationPermissionDenied(permanentlyDenied = true)
        assertEquals(Phase.PermissionPermanentlyDenied, c.state.value.phase)
    }

    @Test
    fun dismiss_routesToTheHost() = runTest(UnconfinedTestDispatcher()) {
        var done = 0
        val c = component(onDone = { done++ })
        c.dismiss()
        assertEquals(1, done)
    }

    @Test
    fun cancelCapture_stopsListening_soNoFurtherEventsAreApplied() = runTest(UnconfinedTestDispatcher()) {
        // The teardown hook (overlay doOnDestroy): a continuous session must stop on destroy, since it
        // doesn't self-terminate. After cancel, a late engine event must not mutate the transcript.
        val events = MutableSharedFlow<TranscriptEvent>(extraBufferCapacity = 16)
        val c = component(events = events)
        c.startDictation()
        events.emit(TranscriptEvent.Final("buy milk"))
        assertEquals("buy milk", c.state.value.transcript)

        c.cancelCapture()
        events.emit(TranscriptEvent.Final("and call mom")) // collector is cancelled — ignored
        assertEquals("buy milk", c.state.value.transcript, "no events are applied after capture is cancelled")
        c.cancelCapture() // idempotent — a second teardown never crashes
    }

    @Test
    fun toCreatePayload_dropsInterDraftParent_keepsExistingItemParent_andConvertsDate() {
        val draftIds = setOf("d1", "d2")

        // A parentId pointing at another draft is an inter-draft relation ⇒ dropped (flat-create v1).
        val interDraft = DraftTask(id = "d2", title = "Child", parentId = "d1")
            .toCreatePayload("America/New_York", draftIds)
        assertNull((interDraft as CreateItem.Payload.Task).payload.parentId)

        // A parentId that isn't one of the drafts is an existing-Item ref ⇒ kept.
        val existing = DraftTask(id = "d3", title = "Child", parentId = "task_abc", completeBy = LocalDate(2026, 1, 2))
            .toCreatePayload("America/New_York", draftIds)
        val task = (existing as CreateItem.Payload.Task).payload
        assertEquals("task_abc", task.parentId)
        assertEquals("2026-01-02T05:00:00Z", task.completeBy, "EST (UTC-5) start-of-day")
    }
}

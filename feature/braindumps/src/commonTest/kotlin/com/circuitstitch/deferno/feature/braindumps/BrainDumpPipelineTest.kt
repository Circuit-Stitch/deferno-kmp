package com.circuitstitch.deferno.feature.braindumps

import com.circuitstitch.deferno.core.agent.DraftTask
import com.circuitstitch.deferno.core.agent.DraftTasks
import com.circuitstitch.deferno.core.agent.Extractor
import com.circuitstitch.deferno.core.agent.InferenceEngine
import com.circuitstitch.deferno.core.agent.InferenceRequest
import com.circuitstitch.deferno.core.agent.InferenceResult
import com.circuitstitch.deferno.core.data.braindump.BrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpNotificationPreference
import com.circuitstitch.deferno.core.data.braindump.InMemoryBrainDumpSalvageCounter
import com.circuitstitch.deferno.core.data.braindump.InMemoryKeepBrainDumpRecordingsPreference
import com.circuitstitch.deferno.core.data.braindump.brainDumpRecordingPlaceholderId
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Instant

private val TODAY = LocalDate.parse("2026-06-14")
private const val TZ = "America/New_York"
private val CREATED = Instant.parse("2026-06-14T09:00:00Z")

/** A fake on-device engine that returns a fixed outcome — Extractor always asks for [DraftTasks]. */
private class StubEngine(private val outcome: InferenceResult<DraftTasks>) : InferenceEngine {
    @Suppress("UNCHECKED_CAST")
    override suspend fun <T : Any> infer(request: InferenceRequest<T>): InferenceResult<T> =
        outcome as InferenceResult<T>
}

private fun success(vararg drafts: DraftTask): InferenceResult<DraftTasks> =
    InferenceResult.Success(DraftTasks(drafts.toList()))

private class FakeTake(
    private val transcript: String,
    private val bytes: ByteArray = byteArrayOf(1, 2, 3, 4),
) : BrainDumpTake {
    var readCount = 0
    override suspend fun transcribe(): String = transcript
    override suspend fun readBytes(): ByteArray {
        readCount++
        return bytes
    }
}

private class FakeDraftSink : BrainDumpDraftSink {
    val upserted = mutableListOf<BrainDumpDraft>()
    override suspend fun upsert(draft: BrainDumpDraft) {
        upserted += draft
    }
}

private class FakeRecordingStore : BrainDumpRecordingStore {
    data class Saved(val id: String, val size: Int, val createdAt: Instant)

    val saved = mutableListOf<Saved>()
    override suspend fun retain(id: String, bytes: ByteArray, createdAt: Instant) {
        saved += Saved(id, bytes.size, createdAt)
    }
}

private class FakeNotifier : BrainDumpNotifier {
    val outcomes = mutableListOf<BrainDumpOutcome>()
    override fun completed(outcome: BrainDumpOutcome) {
        outcomes += outcome
    }
}

private class Harness(
    engine: InferenceResult<DraftTasks>,
    keep: Boolean = true,
    notify: Boolean = true, // tests assert notifier outcomes; the production default (off) is covered explicitly
    val counter: BrainDumpSalvageCounter = InMemoryBrainDumpSalvageCounter(),
) {
    val drafts = FakeDraftSink()
    val recordings = FakeRecordingStore()
    val notifier = FakeNotifier()
    val pipeline = BrainDumpPipeline(
        extractor = Extractor(StubEngine(engine)),
        drafts = drafts,
        recordings = recordings,
        keepRecordings = InMemoryKeepBrainDumpRecordingsPreference(keep),
        salvageCounter = counter,
        notifications = InMemoryBrainDumpNotificationPreference(notify),
        notifier = notifier,
    )
}

class BrainDumpPipelineTest {

    @Test
    fun normalTakeProducesDraftsAndNotifiesTheCount() = runTest {
        val h = Harness(success(DraftTask(id = "1", title = "Buy milk"), DraftTask(id = "2", title = "Call Sam")))

        h.pipeline.process(FakeTake("buy milk and call sam"), TODAY, TZ, CREATED)

        assertEquals(listOf("Buy milk", "Call Sam"), h.drafts.upserted.map { it.title })
        assertTrue(h.drafts.upserted.all { it.status == BrainDumpDraftStatus.Ready && it.createdAt == CREATED })
        assertEquals(listOf<BrainDumpOutcome>(BrainDumpOutcome.Drafts(2)), h.notifier.outcomes)
    }

    @Test
    fun normalTakeRetainsTheRecordingOnlyWhenKeepRecordingsIsOn() = runTest {
        val on = Harness(success(DraftTask(id = "1", title = "Buy milk")), keep = true)
        on.pipeline.process(FakeTake("buy milk"), TODAY, TZ, CREATED)
        assertEquals(
            listOf(brainDumpRecordingPlaceholderId(CREATED)),
            on.recordings.saved.map { it.id },
            "keep on → the WAV is retained under the shared placeholder key",
        )

        val off = Harness(success(DraftTask(id = "1", title = "Buy milk")), keep = false)
        off.pipeline.process(FakeTake("buy milk"), TODAY, TZ, CREATED)
        assertTrue(off.recordings.saved.isEmpty(), "keep off → a normal take does not retain audio")
    }

    @Test
    fun noEngineSalvagesWithTheTranscriptInTheNotes() = runTest {
        // Transcription succeeded, but no engine is configured (Failure.NotConfigured).
        val h = Harness(InferenceResult.Failure.NotConfigured("no engine"))

        h.pipeline.process(FakeTake("remember to water the plants"), TODAY, TZ, CREATED)

        val salvage = h.drafts.upserted.single()
        assertEquals("Brain dump #1", salvage.title)
        assertEquals("remember to water the plants", salvage.notes)
        assertEquals(BrainDumpDraftStatus.Ready, salvage.status)
        assertEquals(listOf<BrainDumpOutcome>(BrainDumpOutcome.Salvaged), h.notifier.outcomes)
    }

    @Test
    fun emptyExtractionSalvagesWithTheTranscriptInTheNotes() = runTest {
        // Transcription succeeded; the engine ran but found no tasks (Success with empty drafts).
        val h = Harness(success())

        h.pipeline.process(FakeTake("um, hello, testing one two"), TODAY, TZ, CREATED)

        val salvage = h.drafts.upserted.single()
        assertEquals("Brain dump #1", salvage.title)
        assertEquals("um, hello, testing one two", salvage.notes)
        assertEquals(listOf<BrainDumpOutcome>(BrainDumpOutcome.Salvaged), h.notifier.outcomes)
    }

    @Test
    fun transcriptionFailureSalvagesWithAReasonNote() = runTest {
        // No transcript at all (blank) — the engine is never consulted; the note is the shared reason string.
        val h = Harness(success(DraftTask(id = "1", title = "ignored")))

        h.pipeline.process(FakeTake(""), TODAY, TZ, CREATED)

        val salvage = h.drafts.upserted.single()
        assertEquals("Brain dump #1", salvage.title)
        assertEquals(SALVAGE_REASON, salvage.notes)
        assertEquals(listOf<BrainDumpOutcome>(BrainDumpOutcome.Salvaged), h.notifier.outcomes)
    }

    @Test
    fun salvageAlwaysRetainsTheRecordingEvenWhenKeepRecordingsIsOff() = runTest {
        val h = Harness(InferenceResult.Failure.NotConfigured("no engine"), keep = false)

        h.pipeline.process(FakeTake("don't lose me"), TODAY, TZ, CREATED)

        val saved = h.recordings.saved.single()
        assertEquals(brainDumpRecordingPlaceholderId(CREATED), saved.id)
        assertEquals(CREATED, saved.createdAt)
    }

    @Test
    fun salvageDraftIdIsKeyedOffTheTakeInstantSoReprocessingUpsertsNotDuplicates() = runTest {
        val counter = InMemoryBrainDumpSalvageCounter()
        val first = Harness(success(), counter = counter)
        first.pipeline.process(FakeTake("same take"), TODAY, TZ, CREATED)
        val second = Harness(success(), counter = counter)
        second.pipeline.process(FakeTake("same take"), TODAY, TZ, CREATED)

        assertEquals(first.drafts.upserted.single().id, second.drafts.upserted.single().id)
        // The monotonic counter still advances per salvage write.
        assertEquals("Brain dump #1", first.drafts.upserted.single().title)
        assertEquals("Brain dump #2", second.drafts.upserted.single().title)
    }

    @Test
    fun extractedDraftIdsAreKeyedOffTheTakeInstantSoReprocessingReplacesNotDuplicates() = runTest {
        // The on-device LLM emits non-deterministic ids per run, so reprocessing a take (the #270 relaunch
        // sweep / BGProcessingTask backstop, after an app death between the draft upsert and the WAV delete)
        // must land on the SAME draft rows — else insertOrReplace inserts a duplicate set. The persisted id is
        // (createdAt, index)-derived, so even with different model ids the two runs upsert identical row ids.
        val first = Harness(success(DraftTask(id = "abc", title = "Buy milk"), DraftTask(id = "xyz", title = "Call Sam")))
        first.pipeline.process(FakeTake("same take"), TODAY, TZ, CREATED)
        val second = Harness(success(DraftTask(id = "p99", title = "Buy milk"), DraftTask(id = "q42", title = "Call Sam")))
        second.pipeline.process(FakeTake("same take"), TODAY, TZ, CREATED)

        assertEquals(
            first.drafts.upserted.map { it.id },
            second.drafts.upserted.map { it.id },
            "reprocessing must reuse the same per-take draft ids so insertOrReplace dedups, not duplicates",
        )
    }

    @Test
    fun completionNotificationFiresOnlyWhenTheOptInIsEnabled() = runTest {
        // Default off (#266): drafts simply appear in the Inbox, nothing interrupts.
        val off = Harness(success(DraftTask(id = "1", title = "Buy milk")), notify = false)
        off.pipeline.process(FakeTake("buy milk"), TODAY, TZ, CREATED)
        assertTrue(off.notifier.outcomes.isEmpty(), "notifications off → no completion notification")

        val on = Harness(success(DraftTask(id = "1", title = "Buy milk")), notify = true)
        on.pipeline.process(FakeTake("buy milk"), TODAY, TZ, CREATED)
        assertEquals(listOf<BrainDumpOutcome>(BrainDumpOutcome.Drafts(1)), on.notifier.outcomes)
    }

    @Test
    fun trivialRecordingPredicateDropsHeaderOnlyTakes() {
        assertTrue(isTrivialRecording(0))
        assertTrue(isTrivialRecording(WAV_HEADER_BYTES), "a header-only WAV captured no audio")
        assertTrue(!isTrivialRecording(WAV_HEADER_BYTES + 1), "any real samples → not trivial")
    }
}

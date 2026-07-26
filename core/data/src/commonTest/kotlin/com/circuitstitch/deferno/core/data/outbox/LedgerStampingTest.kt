package com.circuitstitch.deferno.core.data.outbox

import com.circuitstitch.deferno.core.data.activity.ActivityActionKind
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.data.activity.ActivitySummary
import com.circuitstitch.deferno.core.data.activity.ActivityVerb
import com.circuitstitch.deferno.core.data.activity.summaryInfo
import com.circuitstitch.deferno.core.data.create.ConvertedItem
import com.circuitstitch.deferno.core.data.create.FakeActivityLedgerStore
import com.circuitstitch.deferno.core.data.create.LedgerRecordingItemConverter
import com.circuitstitch.deferno.core.data.create.RecordedLocalActivity
import com.circuitstitch.deferno.core.data.create.StampedItemConverter
import com.circuitstitch.deferno.core.data.task.AttachmentUpload
import com.circuitstitch.deferno.core.data.task.LedgerRecordingTaskDetailRepository
import com.circuitstitch.deferno.core.data.task.StampedAttachmentSource
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The client-minted [ActivityStamp] at the three write seams that record into the activity ledger (#364):
 * the outbox choke-point ([LedgerRecordingOutboxStore]) and the two online-only surfaces that bypass it —
 * the attachment writes ([LedgerRecordingTaskDetailRepository]) and convert ([LedgerRecordingItemConverter]).
 *
 * All three decorators exist to make one value — the stamp's `entryId` — appear on the wire and on the
 * local row simultaneously, because that id is the merge key the `?since=` reconcile dedupes on. So the
 * tests here are about **which body/stamp/verb reaches which side**, not about whether a row was written
 * at all.
 */
class LedgerStampingTest {

    private val t0 = Instant.parse("2026-06-21T12:00:00Z")

    @Test
    fun theStampedBodyGoesOnTheWireWhileTheLedgerRecordsTheUnstampedOriginal() = runTest {
        val delegate = FakeOutboxStore()
        val ledger = FakeActivityLedgerStore()
        val outbox = LedgerRecordingOutboxStore(delegate, ledger, SequentialStamps())

        outbox.enqueue(
            "task:a",
            OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"New"}""", acceptsActivityStamp = true),
            t0,
            before = """{"title":"Old"}""",
        )

        // On the wire: the intent's own keys untouched, with `activity` merged in as a sibling.
        assertEquals(
            """{"title":"New","activity":{"id":"entry-1","at":"2026-06-21T12:00:00Z","source":"mobile"}}""",
            delegate.all.single().request.body,
        )

        // On the ledger: the ORIGINAL body. Recording the stamped one would put a bogus "activity" row in
        // the Activity detail sheet and the Task Trail, because the read-time diff treats every body key
        // as a field the user changed — one that can never be diffed away since `before` never has it.
        val row = ledger.recorded.single()
        assertEquals("""{"title":"New"}""", row.request.body)
        assertFalse(row.request.body!!.contains("activity"))
        // The rest of the row is the write as the caller described it, tagged as this device's own.
        assertEquals(ActivitySource.Mobile, row.source)
        assertEquals("task:a", row.target)
        assertEquals("""{"title":"Old"}""", row.before)
        assertEquals(t0, row.now)
    }

    @Test
    fun everyWritePutsTheSameEntryIdOnTheWireAndOnItsLedgerRow() = runTest {
        val delegate = FakeOutboxStore()
        val ledger = FakeActivityLedgerStore()
        val outbox = LedgerRecordingOutboxStore(delegate, ledger, SequentialStamps())

        // Two writes, so a per-write id can't pass by accidentally being a shared constant.
        outbox.enqueue(
            "task:a",
            OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"x"}""", acceptsActivityStamp = true),
            t0,
        )
        outbox.enqueue(
            "create:Task:b",
            OutboxRequest(OutboxMethod.Post, listOf("tasks"), """{"id":"b"}""", acceptsActivityStamp = true),
            t0,
        )

        val wireIds = delegate.all.map { it.request.activityId() }
        val rowIds = ledger.recorded.map { it.stamp?.entryId }
        // A mismatch here is invisible until the reconcile lands and then silently double-counts every
        // local write: the server files its row under the id it received, so a row recorded under a
        // different id is never replaced in place — it lingers beside its own authoritative twin.
        assertEquals(wireIds, rowIds)
        assertEquals(listOf("entry-1", "entry-2"), wireIds)
        // …and the stamp's `at` is the caller's apply-time, which is what the feed sorts by.
        assertTrue(ledger.recorded.all { it.stamp?.occurredAt == t0 })
    }

    @Test
    fun aRouteThatCannotCarryAStampIsDelegatedUnchangedAndItsLedgerRowIsUnstamped() = runTest {
        val delegate = FakeOutboxStore()
        val ledger = FakeActivityLedgerStore()
        val stamps = SequentialStamps()
        val outbox = LedgerRecordingOutboxStore(delegate, ledger, stamps)

        // Neither route opts in, and the *default* is the load-bearing part: a user-preferences write has
        // a strict payload, so an unexpected `activity` key would 422 — Terminal — and the sender would
        // dead-letter the write rather than merely lose an audit row. A route that forgets to declare the
        // flag lands here too, which is exactly why the field is opt-in.
        val settings = OutboxRequest(OutboxMethod.Patch, listOf("auth", "me", "settings"), """{"tracking_enabled":true}""")
        // The item soft-delete is still a bodiless DELETE upstream, so it can't carry one either.
        val delete = OutboxRequest(OutboxMethod.Delete, listOf("tasks", "a"))
        outbox.enqueue("settings", settings, t0)
        outbox.enqueue("task:a", delete, t0)

        assertEquals(listOf(settings, delete), delegate.all.map { it.request })
        // No stamp is minted at all — a stamp recorded locally but never sent would be a merge key the
        // server has never heard of, which is worse than none: the row is superseded, not deduped.
        assertEquals(0, stamps.minted)
        assertTrue(ledger.recorded.all { it.stamp == null })
        assertNull(ledger.recorded.first().stamp)
        assertEquals(2, ledger.recorded.size)
    }

    @Test
    fun aLedgerFailureNeitherFailsNorLosesTheEnqueue() = runTest {
        val delegate = FakeOutboxStore()
        val ledger = FakeActivityLedgerStore().apply { recordLocalFailure = IllegalStateException("ledger insert failed") }
        val outbox = LedgerRecordingOutboxStore(delegate, ledger, SequentialStamps())

        // Must not throw: the outbox row is the durable source of truth for the user's actual change, and
        // an audit row that couldn't be written is not a reason to drop it. (The feed self-heals anyway —
        // the write still syncs, and its server twin arrives on the next reconcile.)
        outbox.enqueue(
            "task:a",
            OutboxRequest(OutboxMethod.Patch, listOf("tasks", "a"), """{"title":"x"}""", acceptsActivityStamp = true),
            t0,
        )

        assertEquals(1, delegate.all.size)
        assertEquals(1L, outbox.count())
        assertTrue(ledger.recorded.isEmpty())
    }

    // --- the online-only attachment writes (no outbox, so their own decorator) ---

    @Test
    fun aSuccessfulAttachmentWriteRecordsTheExplicitVerbRatherThanOneDerivedFromItsTarget() = runTest {
        val delegate = FakeAttachmentWrites()
        val ledger = FakeActivityLedgerStore()
        val repo = LedgerRecordingTaskDetailRepository(delegate, ledger, now = { t0 }, mintStamp = SequentialStamps())

        assertTrue(repo.uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("a.png", "image/png", ByteArray(1)))))
        assertTrue(repo.deleteAttachment(TaskId("t1"), "att-1"))
        assertTrue(repo.updateAttachmentCaption(TaskId("t1"), "att-1", "on the left"))

        assertEquals(
            listOf(
                ActivityActionKind.AttachmentAdded,
                ActivityActionKind.AttachmentDeleted,
                ActivityActionKind.AttachmentCaptioned,
            ),
            ledger.recorded.map { it.actionKind },
        )
        // Why naming the verb matters: these rows have no verb-bearing outbox target to fall back on, so
        // without an explicit kind the feed would read "Updated a task" for a caption edit — and for the
        // `item:` target shape these paths otherwise suggest, "Moved an item".
        assertEquals(
            listOf(
                ActivitySummary(ActivityVerb.AttachmentAdded),
                ActivitySummary(ActivityVerb.AttachmentDeleted),
                ActivitySummary(ActivityVerb.AttachmentCaptioned),
            ),
            ledger.recorded.map { it.asEntry().summaryInfo() },
        )
        assertTrue(ledger.recorded.all { it.source == ActivitySource.Mobile && it.target == "task:t1" })
    }

    @Test
    fun aFailedAttachmentWriteRecordsNothing() = runTest {
        val ledger = FakeActivityLedgerStore()
        val repo = LedgerRecordingTaskDetailRepository(
            FakeAttachmentWrites(succeeds = false),
            ledger,
            now = { t0 },
            mintStamp = SequentialStamps(),
        )

        assertFalse(repo.uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("a.png", "image/png", ByteArray(1)))))
        assertFalse(repo.deleteAttachment(TaskId("t1"), "att-1"))
        assertFalse(repo.updateAttachmentCaption(TaskId("t1"), "att-1", "nope"))

        // Unlike an enqueued write — durable the moment it is queued, so recorded unconditionally — an
        // attachment write either reached the server now or never happened. Claiming it in the feed would
        // report a change the user's data does not have, and no reconcile would ever take it back.
        assertTrue(ledger.recorded.isEmpty())
    }

    @Test
    fun theAttachmentStampHandedToTheDelegateIsTheOneRecordedOnTheLedger() = runTest {
        val delegate = FakeAttachmentWrites()
        val ledger = FakeActivityLedgerStore()
        val repo = LedgerRecordingTaskDetailRepository(delegate, ledger, now = { t0 }, mintStamp = SequentialStamps())

        repo.uploadAttachments(TaskId("t1"), listOf(AttachmentUpload("a.png", "image/png", ByteArray(1))))
        repo.deleteAttachment(TaskId("t1"), "att-1")

        // Same merge key on the request the server files its row under and on the optimistic row here —
        // the whole reason the stamp is minted at this seam rather than down in the Ktor wire adapter.
        assertEquals(delegate.stamps, ledger.recorded.map { it.stamp })
        assertEquals(listOf("entry-1", "entry-2"), delegate.stamps.map { it.entryId })
        // The stamp carries the write's apply-time, the axis the feed sorts on.
        assertTrue(ledger.recorded.all { it.stamp?.occurredAt == t0 && it.now == t0 })
    }

    // --- the online-only convert (no outbox either, so its own decorator) ---

    @Test
    fun aSuccessfulConvertRecordsTheConvertedVerbUnderTheStampItSent() = runTest {
        val delegate = FakeStampedConvert(ApiResult.Success(ConvertedItem.AsChore(chore("item-1"))))
        val ledger = FakeActivityLedgerStore()
        val converter = LedgerRecordingItemConverter(delegate, ledger, now = { t0 }, mintStamp = SequentialStamps())

        val result = converter.convert("item-1", ConvertItemPayload(type = "chore"))

        // The delegate's answer reaches the caller untouched — the create writer seeds the new-kind row
        // from it, so a decorator that swallowed it would lose the item from every list until a refresh.
        assertEquals(ItemKind.Chore, assertIs<ApiResult.Success<ConvertedItem>>(result).data.kind)
        val row = ledger.recorded.single()
        // The same merge key on the wire and on the local row — convert never passes the outbox
        // choke-point, so this decorator is the only place the two can be made to agree.
        assertEquals(delegate.stamps.single(), row.stamp)
        // The verb is named rather than derived: an `item:{id}` target reads as "Moved an item".
        assertEquals(ActivityActionKind.Converted, row.actionKind)
        assertEquals(ActivitySummary(ActivityVerb.Converted), row.asEntry().summaryInfo())
        assertEquals("item:item-1", row.target)
        assertEquals(ActivitySource.Mobile, row.source)
        assertEquals(t0, row.now)
    }

    @Test
    fun aFailedConvertRecordsNothing() = runTest {
        val ledger = FakeActivityLedgerStore()
        val converter = LedgerRecordingItemConverter(
            FakeStampedConvert(ApiResult.Failure(ApiError.Transport(RuntimeException("offline")))),
            ledger,
            now = { t0 },
            mintStamp = SequentialStamps(),
        )

        val result = converter.convert("item-1", ConvertItemPayload(type = "chore"))

        // A convert that did not reach the server did not happen: a row for it would claim a kind change
        // the user's data does not have, and no reconcile would ever take it back.
        assertTrue(ledger.recorded.isEmpty())
        // …and the failure still surfaces, so the writer can offer the gentle "reconnect to save".
        assertIs<ApiResult.Failure>(result)
    }

    /** The `activity.id` the stamped body actually put on the wire. */
    private fun OutboxRequest.activityId(): String? =
        body?.let { Json.parseToJsonElement(it).jsonObject["activity"]?.jsonObject?.get("id")?.jsonPrimitive?.content }

    /** The item a convert reports back, minimal — only its id and kind matter to the ledger row. */
    private fun chore(id: String) = Chore(
        id = ChoreId(id),
        orgSlug = "u-test",
        title = "trash",
        definitionState = DefinitionState.Active,
        dateCreated = t0,
    )

    /** The recorded row as the feed would read it back, for the read-time verb derivation. */
    private fun RecordedLocalActivity.asEntry() = ActivityEntry(
        seq = 1,
        recordedAt = now,
        source = source,
        target = target,
        method = request.method,
        path = request.path,
        body = request.body,
        entryId = stamp?.entryId,
        occurredAt = stamp?.occurredAt,
        actionKind = actionKind,
    )
}

/** A deterministic minter: `entry-1`, `entry-2`, … so one write's merge key is distinguishable from another's. */
private class SequentialStamps : (Instant) -> ActivityStamp {
    var minted = 0
        private set

    override fun invoke(at: Instant): ActivityStamp = ActivityStamp("entry-${++minted}", at)
}

/**
 * A [StampedAttachmentSource] whose writes all report [succeeds], recording the stamp each was handed.
 *
 * It fakes the *wire* half deliberately: the stamp is an argument only that side of the seam ever receives,
 * so a fake of the app-facing port could not observe what the decorator minted at all.
 */
private class FakeAttachmentWrites(private val succeeds: Boolean = true) : StampedAttachmentSource {

    val stamps = mutableListOf<ActivityStamp>()

    override suspend fun attachments(taskId: TaskId): List<Attachment> = emptyList()

    override suspend fun uploadAttachments(
        taskId: TaskId,
        files: List<AttachmentUpload>,
        stamp: ActivityStamp,
    ): Boolean {
        stamps += stamp
        return succeeds
    }

    override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String, stamp: ActivityStamp): Boolean {
        stamps += stamp
        return succeeds
    }

    override suspend fun updateAttachmentCaption(
        taskId: TaskId,
        attachmentId: String,
        caption: String?,
        stamp: ActivityStamp,
    ): Boolean {
        stamps += stamp
        return succeeds
    }
}

/**
 * A [StampedItemConverter] returning [result], recording the stamp it was handed.
 *
 * The wire half again, for the same reason as [FakeAttachmentWrites]: the stamp only ever crosses that
 * side of the seam, so faking [com.circuitstitch.deferno.core.data.create.ItemConverter] instead could not
 * observe what the decorator minted.
 */
private class FakeStampedConvert(private val result: ApiResult<ConvertedItem>) : StampedItemConverter {

    val stamps = mutableListOf<ActivityStamp>()

    override suspend fun convert(
        id: String,
        payload: ConvertItemPayload,
        stamp: ActivityStamp,
    ): ApiResult<ConvertedItem> {
        stamps += stamp
        return result
    }
}

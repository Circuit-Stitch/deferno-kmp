package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.activity.ActivityActionKind
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.comment.CommentRepository
import com.circuitstitch.deferno.core.data.comment.CommentWriter
import com.circuitstitch.deferno.core.data.history.ItemHistoryRepository
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.ActivityFieldValue
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** In-memory [ItemHistoryRepository] emitting a fixed cached history (oldest-first), no-op refresh. */
private class FakeItemHistory(initial: List<ItemHistoryEvent> = emptyList()) : ItemHistoryRepository {
    val history = MutableStateFlow(initial)
    override fun observe(itemId: String): Flow<List<ItemHistoryEvent>> = history
    override suspend fun refresh(itemId: String) {}
}

/** Minimal read-only comment source for the Trail tests — observe re-emits a fixed cache; writes no-op. */
private class TrailComments(initial: List<Comment> = emptyList()) : CommentRepository, CommentWriter {
    val comments = MutableStateFlow(initial)
    override fun observe(taskId: TaskId): Flow<List<Comment>> = comments
    override suspend fun refresh(taskId: TaskId) {}
    override suspend fun post(taskId: TaskId, body: String) {}
    override suspend fun edit(commentId: String, body: String) {}
    override suspend fun delete(commentId: String) {}
}

private fun item(id: String, title: String) = Item(id = id, kind = ItemKind.Task, title = title)

@OptIn(ExperimentalCoroutinesApi::class)
class TaskDetailTrailTest {

    private fun TestScope.component(
        history: FakeItemHistory,
        comments: TrailComments = TrailComments(),
        items: FakeItemRepository = FakeItemRepository(),
        ledger: List<ActivityEntry> = emptyList(),
    ) = DefaultTaskDetailComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskId = TaskId("a"),
        taskRepository = FakeTaskRepository(listOf(task("a"))),
        output = {},
        commentRepository = comments,
        commentWriter = comments,
        historyRepository = history,
        itemRepository = items,
        observeItemLedger = { flowOf(ledger) },
        coroutineContext = StandardTestDispatcher(testScheduler),
    )

    @Test
    fun trailIsReverseChronologicalNewestFirst() = runTest {
        // A comment at T+1 between two history events (Created at T0, Updated at T2). Newest first.
        val comments = TrailComments(
            listOf(
                Comment(
                    id = "c1",
                    taskId = TaskId("a"),
                    body = "a comment",
                    createdBy = UserId("me"),
                    createdAt = Instant.parse("2026-04-17T10:01:00Z"),
                ),
            ),
        )
        val history = FakeItemHistory(
            listOf(
                ItemHistoryEvent.Created(Instant.parse("2026-04-17T10:00:00Z")),
                ItemHistoryEvent.Updated(Instant.parse("2026-04-17T10:02:00Z"), fields = listOf("deadline")),
            ),
        )

        component(history, comments).state.test {
            var s = awaitItem()
            while (s.activity.size < 3) s = awaitItem()
            val ats = s.activity.map { it.at }
            assertEquals(ats.sortedDescending(), ats, "Trail must be newest-first")
            // The Updated (10:02) leads, then the comment (10:01), then Created (10:00).
            assertTrue(s.activity.first() is ActivityItem.HistoryEvent)
            assertEquals("c1", (s.activity[1] as ActivityItem.Comment).comment.id)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun resolvesPeerTitleFromTheItemCacheAndLeavesUnresolvedNull() = runTest {
        // Split's child resolves; Moved's destination parent is absent from the cache → null ("another item").
        val history = FakeItemHistory(
            listOf(
                ItemHistoryEvent.Split(Instant.parse("2026-04-17T10:00:00Z"), childId = "child-1"),
                ItemHistoryEvent.Moved(
                    Instant.parse("2026-04-17T10:01:00Z"),
                    fromParentId = null,
                    toParentId = "ghost",
                    position = 0,
                ),
            ),
        )
        val items = FakeItemRepository(listOf(item("child-1", "Spun-off subtask")))

        component(history, items = items).state.test {
            var s = awaitItem()
            while (s.activity.size < 2) s = awaitItem()
            val byEvent = s.activity.filterIsInstance<ActivityItem.HistoryEvent>()
            val split = byEvent.first { it.event is ItemHistoryEvent.Split }
            val moved = byEvent.first { it.event is ItemHistoryEvent.Moved }
            assertEquals("Spun-off subtask", split.peerTitle)
            assertNull(moved.peerTitle, "aged-out peer stays null → View shows 'another item'")
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * The ledger edit a Trail row is enriched from must be correlated on the **record** axis, not the
     * actor's wall-clock.
     *
     * A peer edits the title offline at 09:00 and their outbox flushes at 17:00, so the reconciled ledger row
     * has occurred_at=09:00 / recorded_at=17:00 (the server's observed_at — the reconcile found no optimistic
     * twin here). This device then edits the same field at 17:05, where both axes agree. The server history
     * row for the PEER's edit carries the server record time, so keying on occurredAt scores the peer's own
     * edit at eight hours against its own row while the local edit scores five minutes — the local edit wins
     * and this device's old->new values are shown as if they were the peer's. The two axes must disagree for
     * this to be caught at all, which is why the fixture backdates only one of the rows.
     */
    @Test
    fun correlatesAReconciledPeerEditWithItsOwnHistoryRowNotThisDevicesRecentEdit() = runTest {
        val history = FakeItemHistory(
            listOf(
                ItemHistoryEvent.Updated(Instant.parse("2026-04-17T17:00:03Z"), fields = listOf("title")),
                ItemHistoryEvent.Updated(Instant.parse("2026-04-17T17:05:02Z"), fields = listOf("title")),
            ),
        )
        val ledger = listOf(peerTitleEdit(), localTitleEdit())

        component(history, ledger = ledger).state.test {
            var s = awaitItem()
            while (s.activity.filterIsInstance<ActivityItem.HistoryEvent>().count { it.changes.isNotEmpty() } < 2) {
                s = awaitItem()
            }
            val rows = s.activity.filterIsInstance<ActivityItem.HistoryEvent>().associateBy { it.id }
            val peerRow = rows.getValue("history:0").changes.single()
            val localRow = rows.getValue("history:1").changes.single()
            assertEquals(ActivityFieldValue.Present("Peer old"), peerRow.before, "peer's row keeps the peer's values")
            assertEquals(ActivityFieldValue.Present("Peer new"), peerRow.after)
            assertEquals(ActivityFieldValue.Present("Local old"), localRow.before)
            assertEquals(ActivityFieldValue.Present("Local new"), localRow.after)
            cancelAndIgnoreRemainingEvents()
        }
    }

    /** A peer's title edit as the `?since=` reconcile stores it: server-authored, no captured body/before. */
    private fun peerTitleEdit() = ActivityEntry(
        seq = 1,
        // No optimistic twin existed here, so the row entered this ledger at the server's observed_at.
        recordedAt = Instant.parse("2026-04-17T17:00:00Z"),
        source = ActivitySource.Website,
        target = "",
        method = OutboxMethod.Patch,
        path = emptyList(),
        entryId = "peer-1",
        occurredAt = Instant.parse("2026-04-17T09:00:00Z"),
        observedAt = Instant.parse("2026-04-17T17:00:00Z"),
        actionKind = ActivityActionKind.Updated,
        detail = """{"fields":{"title":{"old":"Peer old","new":"Peer new"}}}""",
    )

    /** This device's own title edit — a local write, so its two time axes agree. */
    private fun localTitleEdit() = ActivityEntry(
        seq = 2,
        recordedAt = Instant.parse("2026-04-17T17:05:00Z"),
        source = ActivitySource.Mobile,
        target = "task:a",
        method = OutboxMethod.Patch,
        path = listOf("tasks", "a"),
        body = """{"title":"Local new"}""",
        before = """{"title":"Local old"}""",
    )

    @Test
    fun peerIdExtractsTheStructuralPeerPerEventKind() {
        val t = Instant.parse("2026-04-17T10:00:00Z")
        assertEquals("c", ItemHistoryEvent.Split(t, childId = "c").peerId())
        assertEquals("to", ItemHistoryEvent.Moved(t, fromParentId = "from", toParentId = "to", position = null).peerId())
        assertEquals("from", ItemHistoryEvent.Moved(t, fromParentId = "from", toParentId = null, position = null).peerId())
        assertNull(ItemHistoryEvent.Moved(t, fromParentId = null, toParentId = null, position = null).peerId())
        assertEquals("p", ItemHistoryEvent.ParentAssigned(t, parentId = "p").peerId())
        assertEquals("n", ItemHistoryEvent.FoldedInto(t, nextTaskId = "n").peerId())
        assertEquals("mc", ItemHistoryEvent.MergedChild(t, childId = "mc").peerId())
        assertNull(ItemHistoryEvent.Created(t).peerId())
        assertNull(ItemHistoryEvent.Updated(t, fields = listOf("x")).peerId())
        assertNull(ItemHistoryEvent.MergedIntoParent(t).peerId())
    }
}

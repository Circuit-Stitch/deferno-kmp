package com.circuitstitch.deferno.feature.tasks

import app.cash.turbine.test
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.comment.CommentRepository
import com.circuitstitch.deferno.core.data.comment.CommentWriter
import com.circuitstitch.deferno.core.data.history.ItemHistoryRepository
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
    ) = DefaultTaskDetailComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        taskId = TaskId("a"),
        taskRepository = FakeTaskRepository(listOf(task("a"))),
        output = {},
        commentRepository = comments,
        commentWriter = comments,
        historyRepository = history,
        itemRepository = items,
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

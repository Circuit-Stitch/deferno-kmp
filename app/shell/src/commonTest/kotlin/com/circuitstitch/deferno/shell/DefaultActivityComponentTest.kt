package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.activity.ActivityEntry
import com.circuitstitch.deferno.core.data.activity.ActivitySource
import com.circuitstitch.deferno.core.data.activity.ActivityVerb
import com.circuitstitch.deferno.core.data.outbox.OutboxMethod
import com.circuitstitch.deferno.core.model.Item
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * The Activity feed's read-time join (#260): [DefaultActivityComponent] resolves each ledger row's item id
 * against the item cache to carry the short ref ("#41") + kind, and surfaces a comment's text + its
 * (Task-scoped) item — including a comment row, whose task-tagged target has no `itemId()`, via
 * `commentTaskId()`. An id absent from the cache (a brand-new sequence, or an aged-out/deleted item)
 * resolves to no ref, so the View falls back to the plain copy. Driven on [UnconfinedTestDispatcher] so the
 * `combine` + `stateIn(WhileSubscribed)` upstream runs eagerly when `first` subscribes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultActivityComponentTest {

    private val at = Instant.parse("2026-06-21T12:00:00Z")

    private fun entry(target: String, method: OutboxMethod = OutboxMethod.Patch, body: String? = null) =
        ActivityEntry(seq = 1, recordedAt = at, source = ActivitySource.Mobile, target = target, method = method, path = emptyList(), body = body)

    private fun item(id: String, kind: ItemKind = ItemKind.Task, sequence: Long? = 41) =
        Item(id = id, kind = kind, title = "Ship it", sequence = sequence)

    private fun component(entries: List<ActivityEntry>, items: List<Item>, dispatcher: CoroutineDispatcher) =
        DefaultActivityComponent(
            componentContext = DefaultComponentContext(LifecycleRegistry()),
            observeActivity = { MutableStateFlow(entries) },
            observeItems = { MutableStateFlow(items) },
            coroutineContext = dispatcher,
        )

    @Test
    fun resolvesRefAndKindForATaskUpdateRow() = runTest(UnconfinedTestDispatcher()) {
        val c = component(listOf(entry("task:t-1")), listOf(item("t-1", ItemKind.Task, 41)), UnconfinedTestDispatcher(testScheduler))
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals("t-1", row.itemId)
        assertEquals("#41", row.itemRef)
        assertEquals(ItemKind.Task, row.itemKind)
        assertEquals(ActivityVerb.UpdatedTask, row.summaryInfo.verb)
        assertNull(row.commentBody)
    }

    @Test
    fun resolvesACommentRowToItsTaskWithTheBody() = runTest(UnconfinedTestDispatcher()) {
        // A posted comment: its target embeds the task, and the body carries the text.
        val c = component(
            listOf(entry("comment-create:t-1:c-1", OutboxMethod.Post, """{"body":"take a look?","is_private":false}""")),
            listOf(item("t-1", ItemKind.Task, 41)),
            UnconfinedTestDispatcher(testScheduler),
        )
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals("t-1", row.itemId) // now openable — resolved via commentTaskId()
        assertEquals("#41", row.itemRef)
        assertEquals(ActivityVerb.Commented, row.summaryInfo.verb)
        assertEquals("take a look?", row.commentBody)
    }

    @Test
    fun leavesRefNullWhenTheItemIsNotInTheCache() = runTest(UnconfinedTestDispatcher()) {
        val c = component(listOf(entry("task:gone")), items = emptyList(), UnconfinedTestDispatcher(testScheduler))
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals("gone", row.itemId)
        assertNull(row.itemRef)
        assertNull(row.itemKind)
    }

    @Test
    fun leavesRefNullForABrandNewItemWithNoSequence() = runTest(UnconfinedTestDispatcher()) {
        val c = component(
            listOf(entry("create:Task:t-2", OutboxMethod.Post)),
            listOf(item("t-2", ItemKind.Task, sequence = null)),
            UnconfinedTestDispatcher(testScheduler),
        )
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertNull(row.itemRef)
        assertEquals(ItemKind.Task, row.itemKind)
    }
}

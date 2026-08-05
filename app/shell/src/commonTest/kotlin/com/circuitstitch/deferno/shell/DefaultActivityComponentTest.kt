package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.activity.ActivityActorKind
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
 * (Task-scoped) item — including the two shapes deliberately held back from `itemId()` (the Task Trail's
 * filter key): a comment row, resolved via `commentTaskId()`, and an occurrence row, resolved via
 * `occurrenceItemId()`. An id absent from the cache (a brand-new sequence, or an aged-out/deleted item)
 * resolves to no ref, so the View falls back to the plain copy. Driven on [UnconfinedTestDispatcher] so the
 * `combine` + `stateIn(WhileSubscribed)` upstream runs eagerly when `first` subscribes.
 *
 * Also the home of the **attribution** precedence (#364) — which of the server's actor, an integration's
 * provider, or the acting surface names a row. That decision lives here rather than in each platform's
 * View precisely so it can be tested once, in one place, for both.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DefaultActivityComponentTest {

    private val at = Instant.parse("2026-06-21T12:00:00Z")

    private fun entry(
        target: String,
        method: OutboxMethod = OutboxMethod.Patch,
        body: String? = null,
        source: ActivitySource = ActivitySource.Mobile,
        actorKind: ActivityActorKind? = null,
        provider: String? = null,
    ) = ActivityEntry(
        seq = 1,
        recordedAt = at,
        source = source,
        target = target,
        method = method,
        path = emptyList(),
        body = body,
        actorKind = actorKind,
        provider = provider,
    )

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

    /**
     * A locally-queued check-in (#406). Segment 2 of an `occurrence:` target is the recurring definition's
     * **own item id**, so the row joins the item cache like any other — it used to render with no ref, no
     * kind badge and a dead "Open item", because `itemId()` returned null on a comment that claimed
     * occurrence targets were "keyed by series". They are not; the series id is a separate uuid the item
     * merely carries.
     */
    @Test
    fun resolvesAnOccurrenceRowToItsRecurringDefinition() = runTest(UnconfinedTestDispatcher()) {
        val c = component(
            listOf(entry("occurrence:Habit:h-1:2026-06-21", OutboxMethod.Post)),
            listOf(item("h-1", ItemKind.Habit, 41)),
            UnconfinedTestDispatcher(testScheduler),
        )
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals("h-1", row.itemId) // openable — resolved via occurrenceItemId()
        assertEquals("#41", row.itemRef)
        assertEquals(ItemKind.Habit, row.itemKind)
        assertEquals(ActivityVerb.UpdatedOccurrence, row.summaryInfo.verb)
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

    // --- attribution: which of actor / integration / surface names a row (#364) ---
    //
    // This precedence used to be written twice, once in the Compose View and once in Swift, keyed by
    // string across the ObjC bridge — two copies of one decision with nothing to catch them drifting.
    // It is decided here now, so it is testable once and both platforms render the same answer.

    @Test
    fun anUnreconciledRowIsAttributedToTheSurfaceThatActed() = runTest(UnconfinedTestDispatcher()) {
        // No actor_kind yet: the reconcile hasn't reached this optimistic row, and the source is all we know.
        val c = component(listOf(entry("task:t-1")), listOf(item("t-1")), UnconfinedTestDispatcher(testScheduler))
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals(ActivityAttribution.Surface(ActivitySource.Mobile), row.attribution)
    }

    @Test
    fun aHumanActorAddsNothingOverTheSurfaceSoTheSurfaceStillNamesTheRow() = runTest(UnconfinedTestDispatcher()) {
        val c = component(
            listOf(entry("task:t-1", actorKind = ActivityActorKind.Human, source = ActivitySource.Website)),
            listOf(item("t-1")),
            UnconfinedTestDispatcher(testScheduler),
        )
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals(ActivityAttribution.Surface(ActivitySource.Website), row.attribution)
        assertEquals("Website", row.attribution.token)
    }

    @Test
    fun anAssistantWriteOutranksTheSurfaceItsHumanHappenedToBeOn() = runTest(UnconfinedTestDispatcher()) {
        // The Assistant runs inside the driving human's session, so the row's source is that human's
        // surface. Reporting "via Website" here would be true and useless — the interesting actor is the
        // Assistant, which is exactly why this cannot be a plain source lookup.
        val c = component(
            listOf(entry("task:t-1", actorKind = ActivityActorKind.Assistant, source = ActivitySource.Website)),
            listOf(item("t-1")),
            UnconfinedTestDispatcher(testScheduler),
        )
        val row = c.state.first { it.rows.isNotEmpty() }.rows.single()
        assertEquals(ActivityAttribution.Assistant, row.attribution)
        assertEquals("Assistant", row.attribution.token)
    }

    @Test
    fun aWebhookRowNamesItsIntegrationAndFallsBackToTheGenericWhenTheServerNamedNone() =
        runTest(UnconfinedTestDispatcher()) {
            val named = component(
                listOf(entry("task:t-1", actorKind = ActivityActorKind.Webhook, provider = "github")),
                listOf(item("t-1")),
                UnconfinedTestDispatcher(testScheduler),
            ).state.first { it.rows.isNotEmpty() }.rows.single()
            assertEquals(ActivityAttribution.Integration("github"), named.attribution)

            // A provider the server didn't supply still reads as an integration — the View picks the
            // generic label, rather than the row silently falling back to the surface.
            val anonymous = component(
                listOf(entry("task:t-1", actorKind = ActivityActorKind.Webhook, provider = null)),
                listOf(item("t-1")),
                UnconfinedTestDispatcher(testScheduler),
            ).state.first { it.rows.isNotEmpty() }.rows.single()
            assertEquals(ActivityAttribution.Integration(null), anonymous.attribution)
            assertEquals("Integration", anonymous.attribution.token)
        }

    @Test
    fun everySurfaceTokenIsItsOwnSourceNameSoTheAppleSwitchStaysAFlatMap() {
        // The bridge flattens the sealed type to one token; a Surface must keep reporting the source name
        // the Swift side already switches on, and "Assistant"/"Integration" must not collide with any of
        // them. A rename on either side breaks here rather than silently landing every row on `default`.
        assertEquals(
            ActivitySource.entries.map { it.name },
            ActivitySource.entries.map { ActivityAttribution.Surface(it).token },
        )
        assertEquals(
            emptyList(),
            listOf(ActivityAttribution.Assistant.token, ActivityAttribution.Integration(null).token)
                .filter { it in ActivitySource.entries.map(ActivitySource::name) },
        )
    }
}

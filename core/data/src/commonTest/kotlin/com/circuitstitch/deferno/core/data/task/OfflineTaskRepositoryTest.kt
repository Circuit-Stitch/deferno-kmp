package com.circuitstitch.deferno.core.data.task

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.item.FakeItemSnapshotSource
import com.circuitstitch.deferno.core.data.item.ItemSnapshot
import com.circuitstitch.deferno.core.data.item.ItemSync
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [OfflineTaskRepository] read surface (ADR-0001, #22): the observe `Flow`s, on-demand [hydrate],
 * and online-only [search] — plus that [refresh] delegates the cold sync to [ItemSync] (ADR-0034,
 * #226). The cross-kind reconcile *algorithm* itself is proved separately by `ItemSyncTest`.
 */
class OfflineTaskRepositoryTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun summary(
        id: String,
        title: String = "task-$id",
        state: WorkingState = WorkingState.Open,
        sequence: Long = 1,
        pinned: Boolean = false,
        deletedAt: Instant? = null,
    ) = Task(
        id = TaskId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        workingState = state,
        sequence = sequence,
        pinned = pinned,
        dateCreated = created,
        deletedAt = deletedAt,
        hydration = HydrationState.Summary,
    )

    private fun full(
        id: String,
        title: String = "task-$id",
        description: String? = "body-$id",
        ownerOrgId: String? = "org-$id",
        nextTaskId: String? = "next-$id",
        finishedAt: Instant? = null,
    ) = summary(id, title = title).copy(
        hydration = HydrationState.Full,
        description = description,
        ownerOrgId = ownerOrgId?.let(::OrgId),
        nextTaskId = nextTaskId?.let(::TaskId),
        finishedAt = finishedAt,
    )

    private fun repo(
        local: FakeTaskLocalStore = FakeTaskLocalStore(),
        remote: FakeTaskRemoteSource = FakeTaskRemoteSource(),
        source: FakeItemSnapshotSource = FakeItemSnapshotSource(),
        pendingCreates: FakePendingCreateStore = FakePendingCreateStore(),
    ) = OfflineTaskRepository(
        local,
        remote,
        ItemSync(local, FakeHabitLocalStore(), FakeChoreLocalStore(), FakeEventLocalStore(), source, pendingCreates),
    )

    // --- refresh delegates the cold sync to ItemSync (the /items snapshot reconcile) ---

    @Test
    fun refreshTriggersTheItemSyncWhichPopulatesTheTaskStore() = runTest {
        val local = FakeTaskLocalStore()
        val source = FakeItemSnapshotSource(ItemSnapshot(tasks = listOf(full("a"), full("b"))))

        repo(local, source = source).refresh()

        assertEquals(setOf(TaskId("a"), TaskId("b")), local.allIds())
    }

    // --- hydration on open ---

    @Test
    fun hydrateUpgradesASummaryRowToFull() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(details = mapOf(TaskId("a") to full("a", description = "opened body")))

        repo(local, remote).hydrate(TaskId("a"))

        val row = local.all.getValue(TaskId("a"))
        assertEquals(HydrationState.Full, row.hydration)
        assertEquals("opened body", row.description)
        assertEquals(OrgId("org-a"), row.ownerOrgId)
    }

    @Test
    fun hydratePreservesTheItemsSnapshotDescendantCountsADetailFetchOmits() = runTest {
        // The /items snapshot set the subtree counts; the /tasks/{id} detail doesn't carry them, so
        // opening the Task must NOT blank the collapsed-node progress badge (#226/#227).
        val cached = summary("a").copy(descendantDone = 2, descendantTotal = 5)
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to cached))
        val remote = FakeTaskRemoteSource(details = mapOf(TaskId("a") to full("a"))) // detail has null counts

        repo(local, remote).hydrate(TaskId("a"))

        val row = local.all.getValue(TaskId("a"))
        assertEquals(HydrationState.Full, row.hydration)
        assertEquals(2L, row.descendantDone)
        assertEquals(5L, row.descendantTotal)
    }

    @Test
    fun hydratingAMissingDetailIsANoOp() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(details = emptyMap())

        repo(local, remote).hydrate(TaskId("a"))

        // No detail returned => the summary is left untouched.
        assertEquals(HydrationState.Summary, local.all.getValue(TaskId("a")).hydration)
    }

    // --- observe: re-emits on refresh ---

    @Test
    fun observeTasksEmitsActiveListAndReEmitsOnRefresh() = runTest {
        val local = FakeTaskLocalStore()
        val source = FakeItemSnapshotSource()
        val repository = repo(local, source = source)

        repository.observeTasks().test {
            assertTrue(awaitItem().isEmpty()) // empty cache

            source.snapshot = ItemSnapshot(tasks = listOf(full("a"), full("b")))
            repository.refresh()
            assertEquals(setOf(TaskId("a"), TaskId("b")), awaitItem().map { it.id }.toSet())

            // A second sync that removes "a" re-emits.
            source.snapshot = ItemSnapshot(tasks = listOf(full("b")))
            repository.refresh()
            assertEquals(listOf(TaskId("b")), awaitItem().map { it.id })

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTaskEmitsTheRowAndItsHydration() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(details = mapOf(TaskId("a") to full("a", description = "deep")))
        val repository = repo(local, remote)

        repository.observeTask(TaskId("a")).test {
            assertEquals(HydrationState.Summary, awaitItem()?.hydration)
            repository.hydrate(TaskId("a"))
            val hydrated = awaitItem()
            assertEquals(HydrationState.Full, hydrated?.hydration)
            assertEquals("deep", hydrated?.description)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTaskEmitsNullForAnUnknownId() = runTest {
        val repository = repo()
        repository.observeTask(TaskId("nope")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- global search (#73): online-only one-shot, not the observed live cache ---

    @Test
    fun searchDelegatesToTheRemoteSourceAndCarriesTheFilters() = runTest {
        val remote = FakeTaskRemoteSource(searchResults = listOf(summary("a"), summary("b")))
        val query = TaskSearchQuery(query = "spring", statuses = setOf(WorkingState.InProgress))

        val results = (repo(remote = remote).search(query) as TaskSearchResult.Success).tasks

        assertEquals(listOf(TaskId("a"), TaskId("b")), results.map { it.id })
        assertEquals(query, remote.lastSearchQuery)
    }

    @Test
    fun searchSkipsTheRoundTripForATooShortQuery() = runTest {
        val remote = FakeTaskRemoteSource(searchResults = listOf(summary("a")))

        // Below the contract's 2-char minimum → empty success, with no call reaching the remote.
        val outcome = repo(remote = remote).search(TaskSearchQuery(query = "a"))
        assertTrue((outcome as TaskSearchResult.Success).tasks.isEmpty())
        assertNull(remote.lastSearchQuery)
    }

    @Test
    fun searchAppliesTheClientSideTitleSort() = runTest {
        val remote = FakeTaskRemoteSource(
            searchResults = listOf(summary("1", title = "Zebra"), summary("2", title = "apple")),
        )

        val outcome = repo(remote = remote).search(TaskSearchQuery("task", sort = SearchSort.TitleAsc))

        // Case-insensitive A→Z over the server's order.
        assertEquals(listOf("apple", "Zebra"), (outcome as TaskSearchResult.Success).tasks.map { it.title })
    }

    @Test
    fun searchAppliesTheClientSideDeadlineSort_nullsLast() = runTest {
        val soon = Instant.parse("2026-06-08T00:00:00Z")
        val later = Instant.parse("2026-06-20T00:00:00Z")
        val remote = FakeTaskRemoteSource(
            searchResults = listOf(
                summary("none").copy(completeBy = null),
                summary("later").copy(completeBy = later),
                summary("soon").copy(completeBy = soon),
            ),
        )

        val outcome = repo(remote = remote).search(TaskSearchQuery("task", sort = SearchSort.DeadlineAsc))

        assertEquals(
            listOf(TaskId("soon"), TaskId("later"), TaskId("none")),
            (outcome as TaskSearchResult.Success).tasks.map { it.id },
        )
    }

    @Test
    fun searchDoesNotWriteResultsIntoTheObservedCache() = runTest {
        val local = FakeTaskLocalStore()
        val remote = FakeTaskRemoteSource(searchResults = listOf(summary("hit")))
        val repository = repo(local, remote)

        repository.search(TaskSearchQuery("hit"))

        // Search is a separate read surface (ADR-0001): the observed list stays untouched.
        assertTrue(local.allIds().isEmpty())
    }

    @Test
    fun searchStaysUnavailableWhenTheRemoteFails() = runTest {
        // The repository must not coerce a failed search to an empty success (#73 follow-up): the
        // overlay renders Unavailable distinctly from "no matches".
        val remote = FakeTaskRemoteSource(searchResults = listOf(summary("a")), failNext = true)

        assertEquals(TaskSearchResult.Unavailable, repo(remote = remote).search(TaskSearchQuery("query")))
    }
}

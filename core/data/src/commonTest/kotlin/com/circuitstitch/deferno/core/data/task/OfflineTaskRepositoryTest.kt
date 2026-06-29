package com.circuitstitch.deferno.core.data.task

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.item.FakeItemSnapshotSource
import com.circuitstitch.deferno.core.data.item.ItemSnapshot
import com.circuitstitch.deferno.core.data.item.ItemSync
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The [OfflineTaskRepository] read surface (ADR-0001, #22): the observe `Flow`s, on-demand [hydrate],
 * and the **offline** [search] (#311, ADR-0042 — a local cross-kind read, no network) — plus that
 * [refresh] delegates the cold sync to [ItemSync] (ADR-0034, #226). The cross-kind reconcile *algorithm*
 * itself is proved separately by `ItemSyncTest`.
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
        habit: FakeHabitLocalStore = FakeHabitLocalStore(),
        chore: FakeChoreLocalStore = FakeChoreLocalStore(),
        event: FakeEventLocalStore = FakeEventLocalStore(),
        // Pinned to UTC so the date-range filter test is deterministic across the runner's zone.
        timeZone: TimeZone = TimeZone.UTC,
    ) = OfflineTaskRepository(
        local,
        remote,
        ItemSync(local, habit, chore, event, source, pendingCreates),
        habit,
        chore,
        event,
        timeZone,
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

    // --- global search (#311): an offline local read over the cache, not the observed live list ---

    @Test
    fun searchReturnsCachedTasksMatchingTheTermWithNoNetwork() = runTest {
        // No remote is scripted — the result comes purely from the local cache (ADR-0042 offline-first).
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("a") to summary("a", title = "Spring planting"),
                TaskId("b") to summary("b", title = "Winter prep"),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("spring"))
        assertEquals(listOf("a"), hits.map { it.id })
        assertEquals(ItemKind.Task, hits.single().kind)
    }

    @Test
    fun searchMatchesDescriptionCaseInsensitively() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(TaskId("a") to full("a", title = "Errand", description = "Buy POTTING soil")),
        )
        assertEquals(listOf("a"), repo(local).search(TaskSearchQuery("potting")).map { it.id })
    }

    @Test
    fun searchSpansAllKinds() = runTest {
        // #231 kind-agnostic results survive the offline move: a habit matches the term too.
        val local = FakeTaskLocalStore(mapOf(TaskId("t") to summary("t", title = "Spring task")))
        val habit = FakeHabitLocalStore(mapOf(HabitId("h") to habit("h", title = "Spring stretch")))

        val hits = repo(local, habit = habit).search(TaskSearchQuery("spring"))

        assertEquals(setOf("t" to ItemKind.Task, "h" to ItemKind.Habit), hits.map { it.id to it.kind }.toSet())
    }

    @Test
    fun searchFiltersToItemsWithAttachments() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("with") to summary("with", title = "Has files").copy(attachmentCount = 2, attachmentTotalSize = 100),
                TaskId("without") to summary("without", title = "No files"),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery(query = "files", hasAttachment = true))
        assertEquals(listOf("with"), hits.map { it.id })
        assertEquals(2, hits.single().attachmentCount)
    }

    @Test
    fun searchSortsByAttachmentSizeDescendingNonAttachedLast() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("small") to summary("small", title = "task small").copy(attachmentCount = 1, attachmentTotalSize = 100),
                TaskId("big") to summary("big", title = "task big").copy(attachmentCount = 1, attachmentTotalSize = 5000),
                TaskId("none") to summary("none", title = "task none"),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.AttachmentSizeDesc))
        assertEquals(listOf("big", "small", "none"), hits.map { it.id })
    }

    @Test
    fun searchWithBlankTermButAttachmentFilterReturnsAttachmentItems() = runTest {
        // The Settings → Storage "biggest attachments" deep-link runs with NO text, just the filter+sort.
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("a") to summary("a").copy(attachmentCount = 1, attachmentTotalSize = 10),
                TaskId("b") to summary("b"),
            ),
        )
        val hits = repo(local).search(
            TaskSearchQuery(query = "", hasAttachment = true, sort = SearchSort.AttachmentSizeDesc),
        )
        assertEquals(listOf("a"), hits.map { it.id })
    }

    @Test
    fun searchWithNoConstraintReturnsEmptyRatherThanTheWholeCache() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a"), TaskId("b") to summary("b")))
        assertTrue(repo(local).search(TaskSearchQuery(query = "  ")).isEmpty())
    }

    @Test
    fun searchAppliesTheStatusFilter() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("open") to summary("open", title = "task open", state = WorkingState.Open),
                TaskId("done") to summary("done", title = "task done", state = WorkingState.Done),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", statuses = setOf(WorkingState.Done)))
        assertEquals(listOf("done"), hits.map { it.id })
    }

    @Test
    fun searchAppliesTheTitleSortCaseInsensitively() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(TaskId("1") to summary("1", title = "Zebra task"), TaskId("2") to summary("2", title = "apple task")),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.TitleAsc))
        assertEquals(listOf("apple task", "Zebra task"), hits.map { it.title })
    }

    @Test
    fun searchAppliesTheDeadlineSortNullsLast() = runTest {
        val soon = Instant.parse("2026-06-08T00:00:00Z")
        val later = Instant.parse("2026-06-20T00:00:00Z")
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("none") to summary("none", title = "task none").copy(completeBy = null),
                TaskId("later") to summary("later", title = "task later").copy(completeBy = later),
                TaskId("soon") to summary("soon", title = "task soon").copy(completeBy = soon),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.DeadlineAsc))
        assertEquals(listOf("soon", "later", "none"), hits.map { it.id })
    }

    @Test
    fun searchAppliesTheDateRangeFilterOnTheDeadlineDay() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("in") to summary("in", title = "task in").copy(completeBy = Instant.parse("2026-06-15T09:00:00Z")),
                TaskId("out") to summary("out", title = "task out").copy(completeBy = Instant.parse("2026-07-01T09:00:00Z")),
                TaskId("none") to summary("none", title = "task none").copy(completeBy = null),
            ),
        )
        val hits = repo(local).search(
            TaskSearchQuery("task", fromDate = LocalDate(2026, 6, 1), toDate = LocalDate(2026, 6, 30)),
        )
        assertEquals(listOf("in"), hits.map { it.id })
    }

    @Test
    fun searchDoesNotWriteResultsIntoTheObservedCache() = runTest {
        // Search is a separate read surface (ADR-0001): it never mutates the observed list.
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a", title = "find me")))
        repo(local).search(TaskSearchQuery("find"))
        assertEquals(setOf(TaskId("a")), local.allIds())
    }

    /** A minimal active [Habit] fixture for the cross-kind search test. */
    private fun habit(id: String, title: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )
}

package com.circuitstitch.deferno.core.data.task

import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.data.item.FakeItemLocalStore
import com.circuitstitch.deferno.core.data.item.FakeItemSnapshotSource
import com.circuitstitch.deferno.core.data.item.ItemSnapshot
import com.circuitstitch.deferno.core.data.item.ItemSync
import com.circuitstitch.deferno.core.data.item.cacheOf
import com.circuitstitch.deferno.core.data.item.cached
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.Priority
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.recipe.ParityRecipe
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
 * [refresh] delegates the cold sync to [ItemSync] (ADR-0049, #226). The cross-kind reconcile *algorithm*
 * itself is proved separately by `ItemSyncTest`.
 *
 * **This repository still speaks [Task] (ADR-0056).** The cache is plugin-shaped since #422, so the rows
 * it hands out are built through the recipe's write direction; the fixtures below are still wire rows,
 * and `cached()` puts them into the store the way the reconcile would. Moving the callers onto the
 * plugin record is Phase 4.
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
        local: FakeItemLocalStore = FakeItemLocalStore(),
        remote: FakeTaskRemoteSource = FakeTaskRemoteSource(),
        source: FakeItemSnapshotSource = FakeItemSnapshotSource(),
        pendingCreates: FakePendingCreateStore = FakePendingCreateStore(),
        // Pinned to UTC so the date-range filter test is deterministic across the runner's zone.
        timeZone: TimeZone = TimeZone.UTC,
    ) = OfflineTaskRepository(
        local,
        remote,
        ItemSync(local, source, pendingCreates),
        timeZone,
    )

    /** The cached row for [id] read back as the wire Task the repository hands out. */
    private fun FakeItemLocalStore.task(id: String): Task = ParityRecipe.writeTask(all.getValue(id).item)

    // --- refresh delegates the cold sync to ItemSync (the /items snapshot reconcile) ---

    @Test
    fun refreshTriggersTheItemSyncWhichPopulatesTheCache() = runTest {
        val local = FakeItemLocalStore()
        val source = FakeItemSnapshotSource(ItemSnapshot(tasks = listOf(full("a"), full("b"))))

        repo(local, source = source).refresh()

        assertEquals(setOf("a", "b"), local.allIds())
    }

    // --- hydration on open ---

    @Test
    fun hydrateUpgradesASummaryRowToFull() = runTest {
        val local = FakeItemLocalStore(cacheOf(summary("a").cached()))
        val remote = FakeTaskRemoteSource(details = mapOf(TaskId("a") to full("a", description = "opened body")))

        repo(local, remote).hydrate(TaskId("a"))

        val row = local.task("a")
        assertEquals(HydrationState.Full, row.hydration)
        assertEquals("opened body", row.description)
        assertEquals(OrgId("org-a"), row.ownerOrgId)
    }

    @Test
    fun hydratePreservesTheItemsSnapshotDescendantCountsADetailFetchOmits() = runTest {
        // The /items snapshot set the subtree counts; the /tasks/{id} detail doesn't carry them, so
        // opening the Task must NOT blank the collapsed-node progress badge (#226/#227).
        val cached = summary("a").copy(descendantDone = 2, descendantTotal = 5)
        val local = FakeItemLocalStore(cacheOf(cached.cached()))
        val remote = FakeTaskRemoteSource(details = mapOf(TaskId("a") to full("a"))) // detail has null counts

        repo(local, remote).hydrate(TaskId("a"))

        val row = local.task("a")
        assertEquals(HydrationState.Full, row.hydration)
        assertEquals(2L, row.descendantDone)
        assertEquals(5L, row.descendantTotal)
    }

    @Test
    fun hydratingAMissingDetailIsANoOp() = runTest {
        val local = FakeItemLocalStore(cacheOf(summary("a").cached()))
        val remote = FakeTaskRemoteSource(details = emptyMap())

        repo(local, remote).hydrate(TaskId("a"))

        // No detail returned => the summary is left untouched.
        assertEquals(HydrationState.Summary, local.task("a").hydration)
    }

    // --- observe: re-emits on refresh ---

    @Test
    fun observeTasksEmitsActiveListAndReEmitsOnRefresh() = runTest {
        val local = FakeItemLocalStore()
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

    /**
     * The Task list is narrowed in the store, not in the reader (#422). One cache holds every kind, so
     * `observeTasks` asks for the Task rows and the filter runs in SQL — the recurring rows never reach
     * this Flow at all.
     */
    @Test
    fun observeTasksExcludesTheOtherKindsSharingTheCache() = runTest {
        val local = FakeItemLocalStore(cacheOf(summary("t").cached(), habit("h", "a habit").cached()))

        repo(local).observeTasks().test {
            assertEquals(listOf(TaskId("t")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeTaskEmitsTheRowAndItsHydration() = runTest {
        val local = FakeItemLocalStore(cacheOf(summary("a").cached()))
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

    /**
     * A single-row observe is keyed by id alone, so a row of another kind reads as **null** rather than
     * as a Task with invented fields. The store answers with the row and the kind it came from; only a
     * Task row is rebuilt as a Task.
     */
    @Test
    fun observeTaskEmitsNullForAnIdThatNamesAnotherKind() = runTest {
        val local = FakeItemLocalStore(cacheOf(habit("h", "a habit").cached()))
        repo(local).observeTask(TaskId("h")).test {
            assertNull(awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    // --- global search (#311): an offline local read over the cache, not the observed live list ---

    @Test
    fun searchReturnsCachedTasksMatchingTheTermWithNoNetwork() = runTest {
        // No remote is scripted — the result comes purely from the local cache (ADR-0042 offline-first).
        val local = FakeItemLocalStore(
            cacheOf(
                summary("a", title = "Spring planting").cached(),
                summary("b", title = "Winter prep").cached(),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("spring"))
        assertEquals(listOf("a"), hits.map { it.id })
        assertEquals(ItemKind.Task, hits.single().kind)
    }

    @Test
    fun searchMatchesDescriptionCaseInsensitively() = runTest {
        val local = FakeItemLocalStore(
            cacheOf(full("a", title = "Errand", description = "Buy POTTING soil").cached()),
        )
        assertEquals(listOf("a"), repo(local).search(TaskSearchQuery("potting")).map { it.id })
    }

    @Test
    fun searchSpansAllKinds() = runTest {
        // #231 kind-agnostic results survive the offline move: a habit matches the term too.
        val local = FakeItemLocalStore(
            cacheOf(summary("t", title = "Spring task").cached(), habit("h", "Spring stretch").cached()),
        )

        val hits = repo(local).search(TaskSearchQuery("spring"))

        assertEquals(setOf("t" to ItemKind.Task, "h" to ItemKind.Habit), hits.map { it.id to it.kind }.toSet())
    }

    @Test
    fun searchFiltersToItemsWithAttachments() = runTest {
        val local = FakeItemLocalStore(
            cacheOf(
                summary("with", title = "Has files").copy(attachmentCount = 2, attachmentTotalSize = 100).cached(),
                summary("without", title = "No files").cached(),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery(query = "files", hasAttachment = true))
        assertEquals(listOf("with"), hits.map { it.id })
        assertEquals(2, hits.single().attachmentCount)
    }

    @Test
    fun searchSortsByAttachmentSizeDescendingNonAttachedLast() = runTest {
        val local = FakeItemLocalStore(
            cacheOf(
                summary("small", title = "task small").copy(attachmentCount = 1, attachmentTotalSize = 100).cached(),
                summary("big", title = "task big").copy(attachmentCount = 1, attachmentTotalSize = 5000).cached(),
                summary("none", title = "task none").cached(),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.AttachmentSizeDesc))
        assertEquals(listOf("big", "small", "none"), hits.map { it.id })
    }

    @Test
    fun searchWithBlankTermButAttachmentFilterReturnsAttachmentItems() = runTest {
        // The Settings → Storage "biggest attachments" deep-link runs with NO text, just the filter+sort.
        val local = FakeItemLocalStore(
            cacheOf(
                summary("a").copy(attachmentCount = 1, attachmentTotalSize = 10).cached(),
                summary("b").cached(),
            ),
        )
        val hits = repo(local).search(
            TaskSearchQuery(query = "", hasAttachment = true, sort = SearchSort.AttachmentSizeDesc),
        )
        assertEquals(listOf("a"), hits.map { it.id })
    }

    @Test
    fun searchWithNoConstraintReturnsEmptyRatherThanTheWholeCache() = runTest {
        val local = FakeItemLocalStore(cacheOf(summary("a").cached(), summary("b").cached()))
        assertTrue(repo(local).search(TaskSearchQuery(query = "  ")).isEmpty())
    }

    @Test
    fun searchAppliesTheStatusFilter() = runTest {
        val local = FakeItemLocalStore(
            cacheOf(
                summary("open", title = "task open", state = WorkingState.Open).cached(),
                summary("done", title = "task done", state = WorkingState.Done).cached(),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", statuses = setOf(WorkingState.Done)))
        assertEquals(listOf("done"), hits.map { it.id })
    }

    @Test
    fun searchAppliesTheTitleSortCaseInsensitively() = runTest {
        val local = FakeItemLocalStore(
            cacheOf(summary("1", title = "Zebra task").cached(), summary("2", title = "apple task").cached()),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.TitleAsc))
        assertEquals(listOf("apple task", "Zebra task"), hits.map { it.title })
    }

    @Test
    fun searchAppliesTheDeadlineSortNullsLast() = runTest {
        val soon = Instant.parse("2026-06-08T00:00:00Z")
        val later = Instant.parse("2026-06-20T00:00:00Z")
        val local = FakeItemLocalStore(
            cacheOf(
                summary("none", title = "task none").copy(completeBy = null).cached(),
                summary("later", title = "task later").copy(completeBy = later).cached(),
                summary("soon", title = "task soon").copy(completeBy = soon).cached(),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.DeadlineAsc))
        assertEquals(listOf("soon", "later", "none"), hits.map { it.id })
    }

    // --- the canonical ranked order (#375) ---

    @Test
    fun searchHitsCarryThePrioritySortAxes() = runTest {
        val want = Instant.parse("2026-06-02T23:59:59Z")
        val local = FakeItemLocalStore(
            cacheOf(summary("t", title = "task t").copy(priority = Priority.Fire, targetDate = want).cached()),
        )
        val hit = repo(local).search(TaskSearchQuery("task")).single()
        assertEquals(Priority.Fire, hit.priority)
        assertEquals(want, hit.targetDate)
    }

    @Test
    fun priorityRankSortAppliesTheCanonicalFourTermKey() = runTest {
        // The bucket dominates the dates, and WITHIN a bucket the soft target beats the hard deadline —
        // the server's `priority_sort_key`, verbatim. `targeted` has a far deadline but a near target, so
        // it must out-rank `dueSoon` whose deadline alone is nearer than its (absent) target.
        val local = FakeItemLocalStore(
            cacheOf(
                summary("backlog", title = "task backlog")
                    .copy(priority = Priority.Backlog, completeBy = Instant.parse("2026-06-01T00:00:00Z")).cached(),
                summary("undated", title = "task undated").cached(),
                summary("dueSoon", title = "task dueSoon")
                    .copy(completeBy = Instant.parse("2026-06-10T00:00:00Z")).cached(),
                summary("targeted", title = "task targeted")
                    .copy(
                        targetDate = Instant.parse("2026-06-05T00:00:00Z"),
                        completeBy = Instant.parse("2026-12-01T00:00:00Z"),
                    ).cached(),
                summary("fire", title = "task fire")
                    .copy(priority = Priority.Fire, completeBy = Instant.parse("2026-12-31T00:00:00Z")).cached(),
            ),
        )

        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.PriorityRank))

        assertEquals(listOf("fire", "targeted", "dueSoon", "undated", "backlog"), hits.map { it.id })
    }

    @Test
    fun priorityRankSortKeepsABacklogItemVisible() = runTest {
        // Backlog sinks; it must never be filtered out (that is the whole reason the bucket exists
        // instead of a blocked-by edge, which would hide the item behind a never-finishing blocker).
        val local = FakeItemLocalStore(
            cacheOf(
                summary("b", title = "task b").copy(priority = Priority.Backlog).cached(),
                summary("n", title = "task n").cached(),
            ),
        )
        val hits = repo(local).search(TaskSearchQuery("task", sort = SearchSort.PriorityRank))
        assertEquals(listOf("n", "b"), hits.map { it.id })
    }

    @Test
    fun searchAppliesTheDateRangeFilterOnTheDeadlineDay() = runTest {
        val local = FakeItemLocalStore(
            cacheOf(
                summary("in", title = "task in").copy(completeBy = Instant.parse("2026-06-15T09:00:00Z")).cached(),
                summary("out", title = "task out").copy(completeBy = Instant.parse("2026-07-01T09:00:00Z")).cached(),
                summary("none", title = "task none").copy(completeBy = null).cached(),
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
        val local = FakeItemLocalStore(cacheOf(summary("a", title = "find me").cached()))
        repo(local).search(TaskSearchQuery("find"))
        assertEquals(setOf("a"), local.allIds())
    }

    /** A minimal active [Habit] fixture for the cross-kind search + narrowing tests. */
    private fun habit(id: String, title: String) = Habit(
        id = HabitId(id),
        orgSlug = "u-e4h2qk",
        title = title,
        definitionState = DefinitionState.Active,
        dateCreated = created,
    )
}

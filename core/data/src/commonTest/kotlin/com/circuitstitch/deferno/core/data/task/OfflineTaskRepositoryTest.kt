package com.circuitstitch.deferno.core.data.task

import app.cash.turbine.test
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The reconcile + hydration behaviour of [OfflineTaskRepository] (ADR-0001, #22), run against the
 * in-memory fakes on the ADR-0006 JVM-fast path — this is the heart of the issue. Covers the
 * full-snapshot reconcile (upsert by id, tombstone handling, remove-locally-absent), the
 * hydration-preservation merge that stops a summary refresh from downgrading a full row, on-demand
 * [hydrate], and that the UI-facing `observeTasks()` `Flow` re-emits the active list on reconcile.
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
    ) = OfflineTaskRepository(local, remote)

    // --- reconcile: upsert by id ---

    @Test
    fun refreshUpsertsNewRowsById() = runTest {
        val local = FakeTaskLocalStore()
        val remote = FakeTaskRemoteSource(snapshot = listOf(summary("a"), summary("b")))

        repo(local, remote).refresh()

        assertEquals(setOf(TaskId("a"), TaskId("b")), local.allIds())
    }

    @Test
    fun refreshUpdatesChangedRowsById() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a", title = "old")))
        val remote = FakeTaskRemoteSource(snapshot = listOf(summary("a", title = "new")))

        repo(local, remote).refresh()

        assertEquals("new", local.all.getValue(TaskId("a")).title)
    }

    // --- reconcile: tombstones ---

    @Test
    fun refreshStoresASnapshotTombstoneAsADeletedRowExcludedFromObserve() = runTest {
        val local = FakeTaskLocalStore()
        val remote = FakeTaskRemoteSource(
            snapshot = listOf(
                summary("a"),
                summary("gone", deletedAt = Instant.parse("2026-06-01T00:00:00Z")),
            ),
        )
        val repository = repo(local, remote)

        repository.refresh()

        // The tombstone is kept (present, isDeleted) so the reconcile stays idempotent...
        assertTrue(local.all.containsKey(TaskId("gone")))
        assertTrue(local.all.getValue(TaskId("gone")).isDeleted)
        // ...but it is filtered out of the UI-facing active list.
        repository.observeTasks().test {
            assertEquals(listOf(TaskId("a")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun refreshKeepsATombstonedRowThatIsStillInTheSnapshot() = runTest {
        val tomb = summary("t", deletedAt = Instant.parse("2026-06-01T00:00:00Z"))
        val local = FakeTaskLocalStore(mapOf(TaskId("t") to tomb))
        val remote = FakeTaskRemoteSource(snapshot = listOf(tomb))

        repo(local, remote).refresh()

        // Present in the snapshot => kept as a tombstone, NOT purged.
        assertTrue(local.all.containsKey(TaskId("t")))
        assertTrue(local.all.getValue(TaskId("t")).isDeleted)
    }

    // --- reconcile: remove locally-absent ---

    @Test
    fun refreshRemovesALocalRowAbsentFromTheSnapshot() = runTest {
        val local = FakeTaskLocalStore(
            mapOf(
                TaskId("keep") to summary("keep"),
                TaskId("vanished") to summary("vanished"),
            ),
        )
        val remote = FakeTaskRemoteSource(snapshot = listOf(summary("keep")))

        repo(local, remote).refresh()

        assertEquals(setOf(TaskId("keep")), local.allIds())
        assertFalse(local.all.containsKey(TaskId("vanished")))
    }

    // --- reconcile: hydration preservation ---

    @Test
    fun refreshDoesNotDowngradeAFullRowAndKeepsItsEnrichmentWhileUpdatingSummaryFields() = runTest {
        val finished = Instant.parse("2026-06-02T09:30:00Z")
        val existingFull = full("a", title = "old title", description = "rich body", finishedAt = finished)
            .copy(pinned = false, workingState = WorkingState.Open)
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to existingFull))
        // Snapshot summary changes title/status/pinned but (being a summary) carries null enrichment.
        val incoming = summary("a", title = "new title", state = WorkingState.InProgress, pinned = true)
        val remote = FakeTaskRemoteSource(snapshot = listOf(incoming))

        repo(local, remote).refresh()

        val merged = local.all.getValue(TaskId("a"))
        // Summary fields updated...
        assertEquals("new title", merged.title)
        assertEquals(WorkingState.InProgress, merged.workingState)
        assertTrue(merged.pinned)
        // ...full-only enrichment preserved, row stays Full (no downgrade).
        assertEquals(HydrationState.Full, merged.hydration)
        assertEquals("rich body", merged.description)
        assertEquals(OrgId("org-a"), merged.ownerOrgId)
        assertEquals(TaskId("next-a"), merged.nextTaskId)
        // finishedAt is omitted by the summary endpoint, so the hydrated value must survive too.
        assertEquals(finished, merged.finishedAt)
    }

    @Test
    fun refreshAppliesAFullSnapshotRowAsFull() = runTest {
        // A snapshot row that is itself Full (e.g. from /items) replaces wholesale, enrichment and all.
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(snapshot = listOf(full("a", description = "fresh body")))

        repo(local, remote).refresh()

        val row = local.all.getValue(TaskId("a"))
        assertEquals(HydrationState.Full, row.hydration)
        assertEquals("fresh body", row.description)
    }

    // --- offline-first: failed refresh is a no-op ---

    @Test
    fun aFailedRefreshLeavesTheCacheIntact() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(snapshot = emptyList(), failNext = true)

        repo(local, remote).refresh()

        // The empty result was a failure, not a real empty snapshot, so "a" is NOT purged.
        assertEquals(setOf(TaskId("a")), local.allIds())
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
    fun aSummaryRefreshAfterHydrationDoesNotDowngradeTheRow() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(
            details = mapOf(TaskId("a") to full("a", description = "opened body")),
        )
        val repository = repo(local, remote)

        repository.hydrate(TaskId("a"))
        // A later summary refresh of the same id must not wipe the description.
        remote.snapshot = listOf(summary("a", title = "renamed"))
        repository.refresh()

        val row = local.all.getValue(TaskId("a"))
        assertEquals(HydrationState.Full, row.hydration)
        assertEquals("opened body", row.description)
        assertEquals("renamed", row.title)
    }

    @Test
    fun hydratingAMissingDetailIsANoOp() = runTest {
        val local = FakeTaskLocalStore(mapOf(TaskId("a") to summary("a")))
        val remote = FakeTaskRemoteSource(details = emptyMap())

        repo(local, remote).hydrate(TaskId("a"))

        // No detail returned => the summary is left untouched.
        assertEquals(HydrationState.Summary, local.all.getValue(TaskId("a")).hydration)
    }

    // --- observe: re-emits on reconcile ---

    @Test
    fun observeTasksEmitsActiveListAndReEmitsOnReconcile() = runTest {
        val local = FakeTaskLocalStore()
        val remote = FakeTaskRemoteSource()
        val repository = repo(local, remote)

        repository.observeTasks().test {
            assertTrue(awaitItem().isEmpty()) // empty cache

            remote.snapshot = listOf(summary("a", sequence = 2), summary("b", sequence = 1))
            repository.refresh()
            assertEquals(listOf(TaskId("b"), TaskId("a")), awaitItem().map { it.id }) // sequence order

            // A second reconcile that removes "a" re-emits.
            remote.snapshot = listOf(summary("b", sequence = 1))
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
}

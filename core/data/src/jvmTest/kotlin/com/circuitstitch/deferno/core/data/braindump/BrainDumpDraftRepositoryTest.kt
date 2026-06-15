package com.circuitstitch.deferno.core.data.braindump

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.core.model.BrainDumpDraftId
import com.circuitstitch.deferno.core.model.BrainDumpDraftStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-SQLite integration test for the local-only Brain dump draft store (ADR-0027, ADR-0006
 * JVM-fast path): proves [BrainDumpDraftRepository]'s SQL<->domain mapping (including the nullable
 * date/time columns and the enum status), observe-via-Flow, newest-first ordering, replace-on-upsert,
 * and delete/clear round-trip through a genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`.
 * Timestamps are injected (`Instant.parse`) — never `Clock.System`.
 */
class BrainDumpDraftRepositoryTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun newRepo(): BrainDumpDraftRepository {
        val db = DefernoDatabase(
            JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
        )
        return BrainDumpDraftRepository(db, Dispatchers.Default)
    }

    private fun draft(
        id: String,
        title: String = "draft-$id",
        notes: String? = null,
        completeBy: LocalDate? = null,
        deadlineTimeOfDay: LocalTime? = null,
        status: BrainDumpDraftStatus = BrainDumpDraftStatus.Ready,
        createdAt: Instant = created,
    ) = BrainDumpDraft(
        id = BrainDumpDraftId(id),
        title = title,
        notes = notes,
        completeBy = completeBy,
        deadlineTimeOfDay = deadlineTimeOfDay,
        status = status,
        createdAt = createdAt,
    )

    @Test
    fun upsertRoundTripsEveryField() = runTest {
        val repo = newRepo()
        repo.upsert(
            draft(
                "a",
                title = "buy milk",
                notes = "2% organic",
                completeBy = LocalDate.parse("2026-06-20"),
                deadlineTimeOfDay = LocalTime.parse("09:30"),
                status = BrainDumpDraftStatus.Ready,
            ),
        )

        repo.observeDrafts().test {
            val one = awaitItem().single()
            assertEquals(BrainDumpDraftId("a"), one.id)
            assertEquals("buy milk", one.title)
            assertEquals("2% organic", one.notes)
            assertEquals(LocalDate.parse("2026-06-20"), one.completeBy)
            assertEquals(LocalTime.parse("09:30"), one.deadlineTimeOfDay)
            assertEquals(BrainDumpDraftStatus.Ready, one.status)
            assertEquals(created, one.createdAt)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun nullableColumnsRoundTripAsNull() = runTest {
        val repo = newRepo()
        repo.upsert(draft("a"))

        repo.observeDrafts().test {
            val one = awaitItem().single()
            assertNull(one.notes)
            assertNull(one.completeBy)
            assertNull(one.deadlineTimeOfDay)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeOrdersNewestFirst() = runTest {
        val repo = newRepo()
        repo.upsert(draft("old", createdAt = Instant.parse("2026-05-01T00:00:00Z")))
        repo.upsert(draft("new", createdAt = Instant.parse("2026-06-01T00:00:00Z")))

        repo.observeDrafts().test {
            assertEquals(
                listOf(BrainDumpDraftId("new"), BrainDumpDraftId("old")),
                awaitItem().map { it.id },
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun upsertReplacesByIdAndPreservesNewStatus() = runTest {
        val repo = newRepo()
        repo.upsert(draft("a", title = "first"))
        repo.upsert(draft("a", title = "second", status = BrainDumpDraftStatus.Accepted))

        repo.observeDrafts().test {
            val one = awaitItem().single()
            assertEquals("second", one.title)
            assertEquals(BrainDumpDraftStatus.Accepted, one.status)
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun deleteRemovesOneDraft() = runTest {
        val repo = newRepo()
        repo.upsert(draft("a"))
        repo.upsert(draft("b"))
        repo.delete(BrainDumpDraftId("a"))

        repo.observeDrafts().test {
            assertEquals(listOf(BrainDumpDraftId("b")), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun clearRemovesAllDrafts() = runTest {
        val repo = newRepo()
        repo.upsert(draft("a"))
        repo.upsert(draft("b"))
        repo.clear()

        repo.observeDrafts().test {
            assertTrue(awaitItem().isEmpty())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

package com.circuitstitch.deferno.core.data.item

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.create.FakePendingCreateStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Cadence
import com.circuitstitch.deferno.core.model.CadenceMode
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.ExternalRef
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.HydrationState
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.ItemSource
import com.circuitstitch.deferno.core.model.MonthlyAnchor
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.Recurrence
import com.circuitstitch.deferno.core.model.RecurrenceBound
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.model.recipe.KindRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-SQLite integration test for the one item cache (#22, #71, #422, ADR-0006 JVM-fast path). The
 * commonTest fakes prove the reconcile *algorithm*; this proves the *SQL path* — that a row survives
 * `itemEntity` and comes back as the record it went in as, that `observeActive` is a real re-emitting
 * `Flow`, that `db.transaction { }` commits atomically, and that an [ItemSync] `/items` reconcile drives
 * all of it end to end through a genuine `DefernoDatabase` over an in-memory `JdbcSqliteDriver`.
 *
 * It replaces `SqlDelightTaskLocalStoreTest` and `RecurringLocalStoreTest`, which held one copy each of
 * this same contract over four tables.
 *
 * It is also the only real-SQLite guard on the whole-row `insertOrReplace(ItemEntity)` bind: the `.sq`
 * `VALUES ?` form takes the entity positionally, so a column declared out of order — or a mapping that
 * fills the wrong one — shows up here as a round-trip that no longer returns what it stored. That guard
 * matters more now than it did across four narrow tables: `itemEntity` is the union of all four column
 * sets, so every kind binds the same wide row and a misplaced column is a cross-kind failure.
 */
class SqlDelightItemLocalStoreTest {

    private val created = Instant.parse("2026-05-20T16:11:42Z")

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun newStore(database: DefernoDatabase = db()) =
        SqlDelightItemLocalStore(database, Dispatchers.Default)

    // ── Fixtures ───────────────────────────────────────────────────────────────────────────────

    private fun summary(id: String, title: String = "task-$id", sequence: Long = 1, deletedAt: Instant? = null) =
        Task(
            id = TaskId(id),
            orgSlug = "u-e4h2qk",
            title = title,
            workingState = WorkingState.Open,
            sequence = sequence,
            dateCreated = created,
            deletedAt = deletedAt,
            hydration = HydrationState.Summary,
        )

    private fun full(id: String) = summary(id).copy(
        hydration = HydrationState.Full,
        labels = listOf("home", "urgent"),
        children = listOf(TaskId("c1"), TaskId("c2")),
        ownerOrgId = OrgId("org-$id"),
        description = "body-$id",
        nextTaskId = TaskId("next-$id"),
        finishedAt = Instant.parse("2026-06-02T10:00:00Z"),
        pinned = true,
        descendantDone = 1,
        descendantTotal = 2,
        // Server-derived dependency flags must survive the real-SQLite round-trip (#290).
        blocked = true,
        isBlocker = true,
        // External provenance must survive the round-trip too; the full-equality assertion proves it.
        external = ExternalRef(ItemSource.GitHub, "octo/repo#a", "https://github.com/octo/repo/issues/1"),
    )

    private fun habitOf(id: String, sequence: Long? = null) =
        Habit(HabitId(id), "u-e4h2qk", "habit-$id", DefinitionState.Active, dateCreated = created, sequence = sequence)

    private fun choreOf(id: String, sequence: Long? = null) =
        Chore(ChoreId(id), "u-e4h2qk", "chore-$id", DefinitionState.Active, dateCreated = created, sequence = sequence)

    private fun eventOf(id: String, sequence: Long? = null) =
        Event(EventId(id), "u-e4h2qk", "event-$id", DefinitionState.Active, dateCreated = created, sequence = sequence)

    /** The stored row for [id], read back as the wire row it round-trips to. */
    private suspend fun ItemLocalStore.row(id: String): KindRow? = get(id)?.asKindRow()

    private suspend fun ItemLocalStore.task(id: String): Task? = (row(id) as? KindRow.OfTask)?.task

    private suspend fun ItemLocalStore.habit(id: String): Habit? = (row(id) as? KindRow.OfHabit)?.habit

    private suspend fun ItemLocalStore.chore(id: String): Chore? = (row(id) as? KindRow.OfChore)?.chore

    private suspend fun ItemLocalStore.event(id: String): Event? = (row(id) as? KindRow.OfEvent)?.event

    // ── Round-tripping each kind ───────────────────────────────────────────────────────────────

    @Test
    fun upsertAndGetRoundTripsAFullTaskThroughRealSqlite() = runTest {
        val store = newStore()
        val task = full("a")

        store.upsert(task.cached())

        assertEquals(task, store.task("a"))
    }

    @Test
    fun upsertAndGetRoundTripsAFullHabitThroughRealSqlite() = runTest {
        val store = newStore()
        val habit = Habit(
            id = HabitId("h-1"),
            orgSlug = "u-e4h2qk",
            title = "stretch",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Weekly(listOf("Mon", "Wed"))),
            labels = listOf("health"),
            completeBy = Instant.parse("2026-05-04T08:00:00Z"),
            pinned = true,
            sequence = 5,
            ref = "u-e4h2qk-1",
            dateCreated = created,
            hydration = HydrationState.Full,
            ownerOrgId = OrgId("org-1"),
            description = "body",
            seriesId = "s-1",
            // Server-derived dependency flags must survive the real-SQLite round-trip for recurring kinds
            // too (#290): a recurring row inherits `blocked` from a blocked ancestor.
            blocked = true,
            isBlocker = true,
        )

        store.observeActive().test {
            assertEquals(emptyList(), awaitItem())
            store.upsert(habit.cached())
            assertEquals(listOf("h-1"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        // Faithful round-trip including the flattened recurrence + days.
        assertEquals(habit, store.habit("h-1"))
    }

    @Test
    fun choreRoundTripsCadenceAndDelete() = runTest {
        val store = newStore()
        val chore = Chore(
            id = ChoreId("c-1"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Archived,
            recurrence = Recurrence(Cadence.Daily),
            cadenceMode = CadenceMode.Rolling,
            dateCreated = created,
        )
        store.upsert(chore.cached())
        assertEquals(chore, store.chore("c-1"))

        store.delete("c-1")
        assertNull(store.get("c-1"))
    }

    @Test
    fun eventRoundTripsItsFixedWindowAndNullRecurrence() = runTest {
        val store = newStore()
        val event = Event(
            id = EventId("e-1"),
            orgSlug = "u-e4h2qk",
            title = "standup",
            definitionState = DefinitionState.Active,
            recurrence = null,
            allDay = true,
            completeBy = Instant.parse("2026-04-18T16:00:00Z"),
            endTime = Instant.parse("2026-04-18T17:30:00Z"),
            dateCreated = created,
        )
        store.upsert(event.cached())
        assertEquals(event, store.event("e-1"))
    }

    /**
     * A column one kind owns must be NULL on another kind's row, and the union table is where that could
     * go wrong invisibly: four narrow tables made the separation structural, one wide table makes it a
     * property of the mapping. Two rows of different kinds sharing the table must not bleed into each
     * other, and the whole-row equality on each is what says so.
     */
    @Test
    fun rowsOfDifferentKindsShareTheTableWithoutBleedingIntoEachOther() = runTest {
        val store = newStore()
        val task = full("t")
        val chore = Chore(
            id = ChoreId("c"),
            orgSlug = "u-e4h2qk",
            title = "water the plants",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Daily),
            cadenceMode = CadenceMode.Fixed,
            dateCreated = created,
        )

        store.upsert(task.cached())
        store.upsert(chore.cached())

        assertEquals(task, store.task("t"))
        assertEquals(chore, store.chore("c"))
    }

    /**
     * The `cadence_mode` column against real SQLite (#401). The stored token is a **persisted format**:
     * every row an earlier build cached holds the literal `rolling`/`fixed`/NULL it read straight off
     * the wire, so typing the field only stays a no-migration change if the write side keeps emitting
     * the wire token. Two failures this catches, both silent:
     *
     * - writing the Kotlin variant name (`"Rolling"`) — the next read would no longer recognise it, and
     *   the whole cache would decode as [CadenceMode.Unmodelled] under a name the server never sent;
     * - flattening an unrecognised mode to the default, which reschedules the user's chore on restore
     *   rather than merely failing to render it (the same preservation rule as `Cadence.Unmodelled`).
     */
    @Test
    fun aCadenceModeRoundTripsRealSqliteUnderItsWireTokenIncludingAnUnmodelledOne() = runTest {
        val database = db()
        val store = newStore(database)
        val base = Chore(
            id = ChoreId("c-mode"),
            orgSlug = "u-e4h2qk",
            title = "trash",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.Daily),
            dateCreated = created,
        )

        for (mode in listOf(CadenceMode.Rolling, CadenceMode.Fixed, CadenceMode.Unmodelled("drifting"))) {
            store.upsert(base.copy(cadenceMode = mode).cached())
            assertEquals(mode, store.chore("c-mode")?.cadenceMode, "round-trip of $mode")
        }

        // The COLUMN itself, read raw: the wire token, never the Kotlin variant name.
        store.upsert(base.copy(cadenceMode = CadenceMode.Fixed).cached())
        assertEquals("fixed", database.itemEntityQueries.selectById("c-mode").executeAsOne().cadence_mode)
        store.upsert(base.copy(cadenceMode = CadenceMode.Rolling).cached())
        assertEquals("rolling", database.itemEntityQueries.selectById("c-mode").executeAsOne().cadence_mode)

        // A pre-#401 row — NULL because no client code ever set the field — is Rolling, not an unknown.
        // Simulated by writing the column back to NULL behind the store, which is the state every chore
        // this client created is already in.
        database.itemEntityQueries.insertOrReplace(
            database.itemEntityQueries.selectById("c-mode").executeAsOne().copy(cadence_mode = null),
        )
        assertEquals(CadenceMode.Rolling, store.chore("c-mode")?.cadenceMode)
    }

    /**
     * **The #382 regression, against real SQLite.** The reported symptom was a chore: `every_n_days` had
     * no domain representation, so it persisted as the literal enum name `"Unknown"` and the interval was
     * discarded — an every-30-days and an every-29-days chore became the same cached row, and the
     * original cadence was unrecoverable. The `end` bound had no column at all.
     */
    @Test
    fun anEveryNDaysRuleWithAnAfterCountBoundSurvivesRealSqliteUnchanged() = runTest {
        val store = newStore()
        val chore = Chore(
            id = ChoreId("c-2"),
            orgSlug = "u-e4h2qk",
            title = "replace the filter",
            definitionState = DefinitionState.Active,
            recurrence = Recurrence(Cadence.EveryNDays(30), bound = RecurrenceBound.AfterCount(10)),
            cadenceMode = CadenceMode.Rolling,
            dateCreated = created,
        )

        store.upsert(chore.cached())
        val reread = store.chore("c-2")

        assertEquals(chore, reread)
        // Spelled out, because `assertEquals` on the whole row would still pass if BOTH sides were wrong
        // in the same way — these are the two values the bug destroyed.
        assertEquals(Cadence.EveryNDays(30), reread?.recurrence?.cadence)
        assertEquals(RecurrenceBound.AfterCount(10), reread?.recurrence?.bound)

        // …and the neighbouring interval is genuinely a DIFFERENT cached row, which it was not before.
        val faster = chore.copy(
            id = ChoreId("c-3"),
            recurrence = chore.recurrence?.copy(cadence = Cadence.EveryNDays(29)),
        )
        store.upsert(faster.cached())
        assertEquals(Cadence.EveryNDays(29), store.chore("c-3")?.recurrence?.cadence)
        assertEquals(Cadence.EveryNDays(30), store.chore("c-2")?.recurrence?.cadence)
    }

    /**
     * Every cadence shape and every bound, on all three recurring kinds. They were three `.sq` column
     * sets and three entity mappings, so one of them dropping a column was the failure this caught; they
     * are one of each now, and what it catches is a kind whose encode stopped writing the rule at all.
     */
    @Test
    fun everyCadenceAndBoundRoundTripsThroughRealSqliteOnAllThreeRecurringKinds() = runTest {
        val rules = listOf(
            Recurrence(Cadence.Daily),
            Recurrence(Cadence.EveryNDays(3)),
            Recurrence(Cadence.Weekly(listOf("Mon", "Wed"))),
            Recurrence(Cadence.Monthly(interval = 1, on = MonthlyAnchor.DayOfMonth(15))),
            Recurrence(
                // nth = -1 is the "last Friday" sentinel; it must survive as a NEGATIVE integer.
                Cadence.Monthly(interval = 2, on = MonthlyAnchor.NthWeekday(nth = -1, weekday = "Fri")),
                bound = RecurrenceBound.OnDate(LocalDate(2027, 1, 31)),
            ),
            Recurrence(Cadence.Yearly(interval = 1, month = 6, day = 14)),
            Recurrence(Cadence.Custom("FREQ=WEEKLY;BYDAY=MO,WE;UNTIL=20270131T235959Z")),
            // A cadence this client cannot model still round-trips under its preserved wire token,
            // instead of becoming the literal string "Unknown" and being lost.
            Recurrence(Cadence.Unmodelled("fortnightly")),
        )
        val store = newStore()

        rules.forEachIndexed { i, rule ->
            store.upsert(habitOf("h-$i").copy(recurrence = rule).cached())
            assertEquals(rule, store.habit("h-$i")?.recurrence, "habit $rule")

            store.upsert(choreOf("c-$i").copy(recurrence = rule).cached())
            assertEquals(rule, store.chore("c-$i")?.recurrence, "chore $rule")

            store.upsert(eventOf("e-$i").copy(recurrence = rule).cached())
            assertEquals(rule, store.event("e-$i")?.recurrence, "event $rule")
        }
    }

    // ── The store contract ─────────────────────────────────────────────────────────────────────

    @Test
    fun upsertReplacesByIdAndDeleteRemoves() = runTest {
        val store = newStore()
        store.upsert(summary("a", title = "before").cached())
        store.upsert(summary("a", title = "after").cached())
        assertEquals("after", store.task("a")?.title)
        assertEquals(setOf("a"), store.allIds())

        store.delete("a")
        assertNull(store.get("a"))
        assertTrue(store.allIds().isEmpty())
    }

    /**
     * One order across every kind (#422), where four stores each ordered their own rows and the reader
     * concatenated them. `sequence` is unique per org across kinds, so this is the coherent order the
     * concatenation was approximating.
     */
    @Test
    fun observeActiveOrdersBySequenceAcrossKindsAndExcludesTombstones() = runTest {
        val store = newStore()
        store.upsert(summary("a", sequence = 2).cached())
        store.upsert(habitOf("h", sequence = 1).cached())
        store.upsert(choreOf("c", sequence = 3).cached())
        store.upsert(summary("gone", sequence = 4, deletedAt = Instant.parse("2026-06-01T00:00:00Z")).cached())

        store.observeActive().test {
            assertEquals(listOf("h", "a", "c"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        // Tombstone kept in the full table (reconcile idempotence).
        assertTrue(store.allIds().contains("gone"))
    }

    /** The narrowed read, filtered in SQL — what `observeTasks` uses now that one table holds every kind. */
    @Test
    fun observeActiveOfOneKindReturnsOnlyThatKindsRows() = runTest {
        val store = newStore()
        store.upsert(summary("t", sequence = 1).cached())
        store.upsert(habitOf("h", sequence = 2).cached())
        store.upsert(choreOf("c", sequence = 3).cached())

        store.observeActive(ItemKind.Task).test {
            assertEquals(listOf("t"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        store.observeActive(ItemKind.Habit).test {
            assertEquals(listOf("h"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun observeReEmitsOnceForAReconcileTransaction() = runTest {
        val store = newStore()
        store.observeActive().test {
            assertTrue(awaitItem().isEmpty())
            store.transaction { tx ->
                tx.upsert(summary("a", sequence = 2).cached())
                tx.upsert(habitOf("h", sequence = 1).cached())
            }
            // A single commit-time emission carrying both rows (not one per upsert, and not one per kind).
            assertEquals(listOf("h", "a"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun allIdsAndTransactionRoundTripThroughRealSqlite() = runTest {
        val store = newStore()

        store.transaction { it.upsert(habitOf("h-1").cached()); it.upsert(habitOf("h-2").cached()) }
        store.transaction { it.upsert(choreOf("c-1").cached()) }
        store.transaction { it.upsert(eventOf("e-1").cached()) }

        assertEquals(setOf("h-1", "h-2", "c-1", "e-1"), store.allIds())

        // A transaction that deletes commits atomically; allIds reflects the purge.
        store.transaction { it.delete("h-1") }
        assertEquals(setOf("h-2", "c-1", "e-1"), store.allIds())
    }

    /**
     * A row whose `kind` column names none of the four is **dropped**, not degraded — the one place the
     * entity mapping does not fall back to a default. There is no safe kind to guess: reading a Habit as
     * a Task would give it a working state it has never had. Reachable only by writing the column behind
     * the store, which is exactly the shape a newer build's row would take in an older reader.
     */
    @Test
    fun aRowNamingAnUnknownKindIsDroppedFromTheListRatherThanRenderedAsAFiction() = runTest {
        val database = db()
        val store = newStore(database)
        store.upsert(summary("known", sequence = 1).cached())
        store.upsert(summary("alien", sequence = 2).cached())
        database.itemEntityQueries.insertOrReplace(
            database.itemEntityQueries.selectById("alien").executeAsOne().copy(kind = "Sprint"),
        )

        store.observeActive().test {
            assertEquals(listOf("known"), awaitItem().map { it.id })
            cancelAndIgnoreRemainingEvents()
        }
        assertNull(store.get("alien"))
        // It is still a stored row: `allIds` reads the id column and never decodes, so the reconcile can
        // still purge it rather than leaving a row this build can neither read nor reach.
        assertTrue(store.allIds().contains("alien"))
    }

    // ── The reconcile, end to end ──────────────────────────────────────────────────────────────

    @Test
    fun itemSyncReconcilesTheCacheThroughRealSqlite() = runTest {
        val store = newStore()
        // Seed: a row that will survive (updated), and a row that will vanish (purged).
        store.upsert(summary("keep", title = "old").cached())
        store.upsert(summary("vanished").cached())
        store.upsert(habitOf("h-vanished").cached())

        // A Full /items snapshot: keep updated wholesale (descendant counts and all), fresh inserted,
        // a tombstone kept, and the locally-held vanished rows dropped entirely — of both kinds, in the
        // one transaction that used to be four.
        val source = FakeItemSnapshotSource(
            ItemSnapshot(
                tasks = listOf(
                    full("keep").copy(title = "new"),
                    full("fresh"),
                    summary("tomb", deletedAt = Instant.parse("2026-06-01T00:00:00Z")),
                ),
                habits = listOf(habitOf("h-fresh")),
            ),
        )
        val sync = ItemSync(store, source, FakePendingCreateStore())

        sync.refresh()

        // keep updated wholesale, fresh inserted, vanished purged.
        val keep = assertNotNull(store.task("keep"))
        assertEquals("new", keep.title)
        assertEquals(HydrationState.Full, keep.hydration)
        // the Full row's server-computed subtree counts round-trip through real SQLite (#226).
        assertEquals(1L, keep.descendantDone)
        assertEquals(2L, keep.descendantTotal)
        // and the server-derived dependency flags round-trip too (#290).
        assertTrue(keep.blocked)
        assertTrue(keep.isBlocker)
        assertEquals(WorkingState.Open, store.task("fresh")?.workingState)
        assertNull(store.get("vanished"))
        assertNull(store.get("h-vanished"))
        assertEquals(ItemKind.Habit, store.get("h-fresh")?.kind)
        // tombstone present + isDeleted, excluded from active.
        assertTrue(store.get("tomb")?.item?.core?.isDeleted == true)

        store.observeActive().test {
            val activeIds = awaitItem().map { it.id }.toSet()
            assertEquals(setOf("keep", "fresh", "h-fresh"), activeIds)
            assertFalse(activeIds.contains("tomb"))
            cancelAndIgnoreRemainingEvents()
        }
    }

    /**
     * A row whose kind changed server-side is one upsert in place (#422). The four-store reconcile could
     * not express it as one act: the row arrived in the new kind's snapshot list and vanished from the
     * old kind's, so it was an insert in one table and an orphan purge in another, across two
     * transactions — with a window in between where the tree held it twice.
     */
    @Test
    fun aRowWhoseKindChangedServerSideIsOneUpsertThroughRealSqlite() = runTest {
        val store = newStore()
        store.upsert(summary("x").cached())

        val source = FakeItemSnapshotSource(ItemSnapshot(habits = listOf(habitOf("x"))))
        ItemSync(store, source, FakePendingCreateStore()).refresh()

        assertEquals(setOf("x"), store.allIds())
        assertEquals(ItemKind.Habit, store.get("x")?.kind)
        assertIs<KindRow.OfHabit>(store.row("x"))
    }
}

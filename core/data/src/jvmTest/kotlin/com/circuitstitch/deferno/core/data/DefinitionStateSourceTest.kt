package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.chore.SqlDelightChoreLocalStore
import com.circuitstitch.deferno.core.data.definition.DefinitionRef
import com.circuitstitch.deferno.core.data.definition.SqlDelightDefinitionStateSource
import com.circuitstitch.deferno.core.data.event.SqlDelightEventLocalStore
import com.circuitstitch.deferno.core.data.habit.SqlDelightHabitLocalStore
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Instant

/**
 * Real-SQLite integration for the kind-neutral definition-state seam (#390, ADR-0053 decision 4,
 * ADR-0006 JVM-fast path).
 *
 * The light switch is the third input of the occurrence-state reading, and the one that keeps a derived
 * Missed honest: archiving a definition does not clear its recurring cursor server-side, so a habit
 * switched off in January would otherwise read as overdue every day since. This proves the three
 * recurring tables answer through one map keyed by [DefinitionRef], that the map follows an archive
 * live, and that the two "no answer" cases — a Task id and an id this device has not cached — resolve
 * to `null` rather than to a state that would license a Missed.
 */
class DefinitionStateSourceTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    private fun db() = DefernoDatabase(
        JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DefernoDatabase.Schema.create(it) },
    )

    private fun habit(id: String, state: DefinitionState = DefinitionState.Active, deletedAt: Instant? = null) =
        Habit(HabitId(id), "u-e4h2qk", "stretch", state, dateCreated = created, deletedAt = deletedAt)

    private fun chore(id: String, state: DefinitionState = DefinitionState.Active) =
        Chore(ChoreId(id), "u-e4h2qk", "bins", state, dateCreated = created)

    private fun event(id: String, state: DefinitionState = DefinitionState.Active) =
        Event(EventId(id), "u-e4h2qk", "standup", state, dateCreated = created)

    @Test
    fun observeAllAnswersForAllThreeKindsAndFollowsAnArchiveLive() = runTest {
        val db = db()
        val habits = SqlDelightHabitLocalStore(db, Dispatchers.Default)
        SqlDelightChoreLocalStore(db, Dispatchers.Default).upsert(chore("chr-9"))
        SqlDelightEventLocalStore(db, Dispatchers.Default).upsert(event("evt-3", DefinitionState.Archived))
        habits.upsert(habit("hab-1"))

        SqlDelightDefinitionStateSource(db, Dispatchers.Default).observeAll().test {
            assertEquals(
                mapOf(
                    DefinitionRef(ItemKind.Habit, "hab-1") to DefinitionState.Active,
                    DefinitionRef(ItemKind.Chore, "chr-9") to DefinitionState.Active,
                    DefinitionRef(ItemKind.Event, "evt-3") to DefinitionState.Archived,
                ),
                awaitItem(),
            )

            // The switch is flipped, not the row removed — the definition still exists and still has a
            // stale cursor. The reading must see the new value on the next frame, not on the next sync.
            habits.upsert(habit("hab-1", DefinitionState.Archived))
            assertEquals(
                DefinitionState.Archived,
                awaitItem()[DefinitionRef(ItemKind.Habit, "hab-1")],
            )
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun aTombstonedDefinitionHasNoLightSwitchAtAll() = runTest {
        val db = db()
        SqlDelightHabitLocalStore(db, Dispatchers.Default).upsert(
            habit("hab-gone", deletedAt = Instant.parse("2026-06-01T00:00:00Z")),
        )
        val source = SqlDelightDefinitionStateSource(db, Dispatchers.Default)

        // A soft-deleted definition resolves to `null`, which the resolver reads as Unknown. Reporting
        // Active for a deleted row would license a Missed on a firing of something that no longer exists.
        assertNull(source.get(ItemKind.Habit, "hab-gone"))
        source.observeAll().test {
            assertEquals(emptyMap(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun getAnswersPerKindAndIsNullForATaskIdAndForAnUnknownId() = runTest {
        val db = db()
        SqlDelightHabitLocalStore(db, Dispatchers.Default).upsert(habit("hab-1"))
        SqlDelightChoreLocalStore(db, Dispatchers.Default).upsert(chore("chr-9", DefinitionState.Archived))
        SqlDelightEventLocalStore(db, Dispatchers.Default).upsert(event("evt-3"))
        val source = SqlDelightDefinitionStateSource(db, Dispatchers.Default)

        assertEquals(DefinitionState.Active, source.get(ItemKind.Habit, "hab-1"))
        assertEquals(DefinitionState.Archived, source.get(ItemKind.Chore, "chr-9"))
        assertEquals(DefinitionState.Active, source.get(ItemKind.Event, "evt-3"))

        // A Task has no light switch to look up — its lifecycle is a WorkingState — and an id this
        // device has never cached has nothing to find. Both are `null`, never a defaulted Active.
        assertNull(source.get(ItemKind.Task, "hab-1"))
        assertNull(source.get(ItemKind.Habit, "hab-NEVER-SEEN"))
        assertNull(source.get(ItemKind.Chore, "hab-1"))
    }
}

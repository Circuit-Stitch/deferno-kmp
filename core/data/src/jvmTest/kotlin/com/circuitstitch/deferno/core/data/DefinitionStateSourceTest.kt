package com.circuitstitch.deferno.core.data

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import app.cash.turbine.test
import com.circuitstitch.deferno.core.data.definition.DefinitionRef
import com.circuitstitch.deferno.core.data.definition.SqlDelightDefinitionStateSource
import com.circuitstitch.deferno.core.data.item.SqlDelightItemLocalStore
import com.circuitstitch.deferno.core.data.item.cached
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.WorkingState
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
 * switched off in January would otherwise read as overdue every day since. This proves every recurring
 * kind answers through one map keyed by [DefinitionRef], that the map follows an archive live, and that
 * the two "no answer" cases — a Task id and an id this device has not cached — resolve to `null` rather
 * than to a state that would license a Missed.
 *
 * **One query where there were three (#422).** It read three kind-qualified queries over three tables;
 * one table means one, and the `kind` a [DefinitionRef] carries comes off the row rather than from which
 * query answered. A Task is excluded by its NULL `definition_state` rather than by not being asked.
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
        val items = SqlDelightItemLocalStore(db, Dispatchers.Default)
        items.upsert(chore("chr-9").cached())
        items.upsert(event("evt-3", DefinitionState.Archived).cached())
        items.upsert(habit("hab-1").cached())

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
            items.upsert(habit("hab-1", DefinitionState.Archived).cached())
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
        SqlDelightItemLocalStore(db, Dispatchers.Default).upsert(
            habit("hab-gone", deletedAt = Instant.parse("2026-06-01T00:00:00Z")).cached(),
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
        val items = SqlDelightItemLocalStore(db, Dispatchers.Default)
        items.upsert(habit("hab-1").cached())
        items.upsert(chore("chr-9", DefinitionState.Archived).cached())
        items.upsert(event("evt-3").cached())
        val source = SqlDelightDefinitionStateSource(db, Dispatchers.Default)

        assertEquals(DefinitionState.Active, source.get(ItemKind.Habit, "hab-1"))
        assertEquals(DefinitionState.Archived, source.get(ItemKind.Chore, "chr-9"))
        assertEquals(DefinitionState.Active, source.get(ItemKind.Event, "evt-3"))

        // A Task has no light switch to look up — its lifecycle is a WorkingState — and an id this
        // device has never cached has nothing to find. Both are `null`, never a defaulted Active.
        assertNull(source.get(ItemKind.Task, "hab-1"))
        assertNull(source.get(ItemKind.Habit, "hab-NEVER-SEEN"))

        // The caller's kind no longer narrows the lookup (#422): the id is unique across kinds, so a
        // stale or mis-tagged kind reads the row it names rather than reading `null` off a table that
        // could never have held it. Only the Task guard above is still a kind decision.
        assertEquals(DefinitionState.Active, source.get(ItemKind.Chore, "hab-1"))
    }

    /** A cached Task carries no light switch at all — the query excludes it on a NULL column. */
    @Test
    fun aCachedTaskContributesNoLightSwitch() = runTest {
        val db = db()
        SqlDelightItemLocalStore(db, Dispatchers.Default).upsert(
            Task(TaskId("t-1"), "u-e4h2qk", "buy milk", WorkingState.Open, dateCreated = created).cached(),
        )
        val source = SqlDelightDefinitionStateSource(db, Dispatchers.Default)

        assertNull(source.get(ItemKind.Habit, "t-1"))
        source.observeAll().test {
            assertEquals(emptyMap(), awaitItem())
            cancelAndIgnoreRemainingEvents()
        }
    }
}

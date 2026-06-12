package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.create.FakeChoreLocalStore
import com.circuitstitch.deferno.core.data.create.FakeEventLocalStore
import com.circuitstitch.deferno.core.data.create.FakeHabitLocalStore
import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.ChoreId
import com.circuitstitch.deferno.core.model.DefinitionState
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.HabitId
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

/**
 * [LocalStoreSeriesKindSource] (#74): snapshots the locally-cached recurring definitions into the
 * `series_id -> kind` index a kind-less calendar feed row resolves its occurrence write against.
 */
class SeriesKindSourceTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    @Test
    fun indexesEveryLocallyKnownDefinitionByItsKind() = runTest {
        val habit = Habit(id = HabitId("h-1"), orgSlug = "u-e4h2qk", title = "stretch", definitionState = DefinitionState.Active, dateCreated = created)
        val chore = Chore(id = ChoreId("c-1"), orgSlug = "u-e4h2qk", title = "trash", definitionState = DefinitionState.Active, dateCreated = created)
        val event = Event(id = EventId("e-1"), orgSlug = "u-e4h2qk", title = "standup", definitionState = DefinitionState.Active, dateCreated = created)
        val source = LocalStoreSeriesKindSource(
            habits = FakeHabitLocalStore(mapOf(habit.id to habit)),
            chores = FakeChoreLocalStore(mapOf(chore.id to chore)),
            events = FakeEventLocalStore(mapOf(event.id to event)),
        )

        assertEquals(
            mapOf("h-1" to ItemKind.Habit, "c-1" to ItemKind.Chore, "e-1" to ItemKind.Event),
            source.currentSeriesKinds(),
        )
    }

    @Test
    fun emptyCachesYieldAnEmptyIndex() = runTest {
        val source = LocalStoreSeriesKindSource(FakeHabitLocalStore(), FakeChoreLocalStore(), FakeEventLocalStore())

        assertEquals(emptyMap(), source.currentSeriesKinds())
    }
}

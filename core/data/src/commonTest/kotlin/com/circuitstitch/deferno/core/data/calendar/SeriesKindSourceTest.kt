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
 * [LocalStoreSeriesKindSource] (#74, #380): snapshots the locally-cached recurring definitions into the
 * `series_id -> kind` index, the offline fallback a cached calendar row resolves its kind against for a
 * window the feed has not refreshed.
 *
 * The index is keyed by the definition's **`series_id`**, which for a real recurring item is a
 * different uuid from its item id — the consumer reads `index[row.series_id]`, so keying by item id
 * (what this did before) could never hit and every firing rendered read-only.
 */
class SeriesKindSourceTest {

    private val created = Instant.parse("2026-05-04T01:53:05Z")

    private fun habit(id: String, seriesId: String?) =
        Habit(id = HabitId(id), orgSlug = "u-e4h2qk", title = "stretch", definitionState = DefinitionState.Active, dateCreated = created, seriesId = seriesId)

    @Test
    fun indexesEveryLocallyKnownDefinitionByItsSeriesId() = runTest {
        val habit = habit("h-1", "hs-1")
        val chore = Chore(id = ChoreId("c-1"), orgSlug = "u-e4h2qk", title = "trash", definitionState = DefinitionState.Active, dateCreated = created, seriesId = "cs-1")
        val event = Event(id = EventId("e-1"), orgSlug = "u-e4h2qk", title = "standup", definitionState = DefinitionState.Active, dateCreated = created, seriesId = "es-1")
        val source = LocalStoreSeriesKindSource(
            habits = FakeHabitLocalStore(mapOf(habit.id to habit)),
            chores = FakeChoreLocalStore(mapOf(chore.id to chore)),
            events = FakeEventLocalStore(mapOf(event.id to event)),
        )

        // The SERIES ids, not the item ids — the whole point of the index.
        assertEquals(
            mapOf("hs-1" to ItemKind.Habit, "cs-1" to ItemKind.Chore, "es-1" to ItemKind.Event),
            source.currentSeriesKinds(),
        )
    }

    @Test
    fun aDefinitionWithNoSeriesIdIsAbsent_notKeyedByItsItemId() = runTest {
        // A phantom `item_id -> kind` entry can never be looked up (the consumer keys on series_id) but
        // it CAN collide with a real series id, so it is dropped rather than smuggled in. Proved for all
        // three kinds — each has its own `seriesId?.let` and each would leak independently.
        val source = LocalStoreSeriesKindSource(
            habits = FakeHabitLocalStore(mapOf(HabitId("h-1") to habit("h-1", null), HabitId("h-2") to habit("h-2", "hs-2"))),
            chores = FakeChoreLocalStore(
                mapOf(
                    ChoreId("c-1") to Chore(id = ChoreId("c-1"), orgSlug = "u-e4h2qk", title = "trash", definitionState = DefinitionState.Active, dateCreated = created),
                    ChoreId("c-2") to Chore(id = ChoreId("c-2"), orgSlug = "u-e4h2qk", title = "bins", definitionState = DefinitionState.Active, dateCreated = created, seriesId = "cs-2"),
                ),
            ),
            events = FakeEventLocalStore(
                mapOf(
                    EventId("e-1") to Event(id = EventId("e-1"), orgSlug = "u-e4h2qk", title = "standup", definitionState = DefinitionState.Active, dateCreated = created),
                    EventId("e-2") to Event(id = EventId("e-2"), orgSlug = "u-e4h2qk", title = "retro", definitionState = DefinitionState.Active, dateCreated = created, seriesId = "es-2"),
                ),
            ),
        )

        assertEquals(
            mapOf("hs-2" to ItemKind.Habit, "cs-2" to ItemKind.Chore, "es-2" to ItemKind.Event),
            source.currentSeriesKinds(),
        )
    }

    @Test
    fun emptyCachesYieldAnEmptyIndex() = runTest {
        val source = LocalStoreSeriesKindSource(FakeHabitLocalStore(), FakeChoreLocalStore(), FakeEventLocalStore())

        assertEquals(emptyMap(), source.currentSeriesKinds())
    }
}

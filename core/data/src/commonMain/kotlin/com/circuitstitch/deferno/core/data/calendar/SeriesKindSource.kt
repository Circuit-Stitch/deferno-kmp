package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.flow.first

/**
 * Snapshots the recurring definitions this client knows into a `series_id -> kind` index (#74) — the
 * bridge that makes a kind-less calendar feed row actionable (the occurrence endpoints are kind-scoped).
 * The calendar repository reseeds the [CalendarLocalStore] index from this on each window refresh.
 *
 * **Best-effort by design.** It reads the *local* Habit/Chore/Event caches (there is no list-definitions
 * endpoint to enumerate them all), so a definition this device hasn't cached (e.g. created on the web)
 * is absent — its feed rows resolve to a `null` kind and render read-only. A backend follow-up adding
 * `kind` to the feed row collapses this whole bridge to nothing.
 */
fun interface SeriesKindSource {
    /** A snapshot of the locally-known recurring definitions as a `series_id -> kind` index. */
    suspend fun currentSeriesKinds(): Map<String, ItemKind>
}

/**
 * The production [SeriesKindSource] over the three recurring-definition local stores (#71). It takes a
 * one-shot snapshot of each store's active-definition `Flow` ([first]) and tags every id with its kind.
 */
class LocalStoreSeriesKindSource(
    private val habits: HabitLocalStore,
    private val chores: ChoreLocalStore,
    private val events: EventLocalStore,
) : SeriesKindSource {

    override suspend fun currentSeriesKinds(): Map<String, ItemKind> {
        val index = mutableMapOf<String, ItemKind>()
        habits.observeActive().first().forEach { index[it.id.value] = ItemKind.Habit }
        chores.observeActive().first().forEach { index[it.id.value] = ItemKind.Chore }
        events.observeActive().first().forEach { index[it.id.value] = ItemKind.Event }
        return index
    }
}

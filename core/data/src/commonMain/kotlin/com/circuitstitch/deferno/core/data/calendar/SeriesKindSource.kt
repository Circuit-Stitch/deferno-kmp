package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.data.chore.ChoreLocalStore
import com.circuitstitch.deferno.core.data.event.EventLocalStore
import com.circuitstitch.deferno.core.data.habit.HabitLocalStore
import com.circuitstitch.deferno.core.model.ItemKind
import kotlinx.coroutines.flow.first

/**
 * Snapshots the recurring definitions this client knows into a `series_id -> kind` index (#74) — the
 * **offline fallback** that resolves a cached calendar row's kind for a window the feed has not
 * refreshed (the occurrence endpoints are kind-scoped, so an unresolved row renders read-only).
 *
 * Since #311 the feed row carries its own `kind`, and `OfflineCalendarRepository.refreshWindow` seeds
 * the index primarily from *those rows* — this source is unioned in beneath them. It stays because the
 * feed only speaks for the window it just returned: a row cached from an earlier window, or from a
 * build before `kind` was modelled, still resolves through here.
 *
 * **Best-effort by design.** It reads the *local* Habit/Chore/Event caches (there is no list-definitions
 * endpoint to enumerate them all), so a definition this device hasn't cached (e.g. created on the web)
 * is absent from it — which is exactly the case the feed-row seeding now covers.
 */
fun interface SeriesKindSource {
    /** A snapshot of the locally-known recurring definitions as a `series_id -> kind` index. */
    suspend fun currentSeriesKinds(): Map<String, ItemKind>
}

/**
 * The production [SeriesKindSource] over the three recurring-definition local stores (#71). It takes a
 * one-shot snapshot of each store's active-definition `Flow` ([first]) and tags every id with its kind.
 *
 * **Keyed by the definition's `series_id`, not its item id (#380).** The consumer looks up
 * `index[row.series_id]`, and for a real recurring item those two values are different uuids — so the
 * old item-id keying could never hit, which is what made every firing render read-only. A definition
 * with no `series_id` contributes nothing rather than a phantom entry under its item id.
 */
class LocalStoreSeriesKindSource(
    private val habits: HabitLocalStore,
    private val chores: ChoreLocalStore,
    private val events: EventLocalStore,
) : SeriesKindSource {

    override suspend fun currentSeriesKinds(): Map<String, ItemKind> {
        val index = mutableMapOf<String, ItemKind>()
        habits.observeActive().first().forEach { habit -> habit.seriesId?.let { index[it] = ItemKind.Habit } }
        chores.observeActive().first().forEach { chore -> chore.seriesId?.let { index[it] = ItemKind.Chore } }
        events.observeActive().first().forEach { event -> event.seriesId?.let { index[it] = ItemKind.Event } }
        return index
    }
}

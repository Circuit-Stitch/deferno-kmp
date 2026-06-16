package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Task

/**
 * The `GET /items` cold snapshot (ADR-0034, #226), partitioned into the four [Item kind] domain lists
 * the [ItemSync] reconciles into their per-kind local stores. The server applies the
 * **done-visibility window** before returning, so this is *already* the windowed set — long-aged
 * terminal items are simply absent and fall out of the cache on reconcile, with no client-side window
 * math. Every row is fully hydrated ([com.circuitstitch.deferno.core.model.HydrationState.Full]).
 */
data class ItemSnapshot(
    val tasks: List<Task> = emptyList(),
    val habits: List<Habit> = emptyList(),
    val chores: List<Chore> = emptyList(),
    val events: List<Event> = emptyList(),
)

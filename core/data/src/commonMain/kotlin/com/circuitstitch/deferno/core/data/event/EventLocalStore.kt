package com.circuitstitch.deferno.core.data.event

import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import kotlinx.coroutines.flow.Flow

/** The local source-of-truth port for Events (ADR-0001, #71) — sibling of `HabitLocalStore`. */
interface EventLocalStore {
    fun observeActive(): Flow<List<Event>>
    fun observe(id: EventId): Flow<Event?>
    suspend fun get(id: EventId): Event?
    suspend fun upsert(event: Event)
    suspend fun delete(id: EventId)
}

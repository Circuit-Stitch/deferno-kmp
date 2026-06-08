package com.circuitstitch.deferno.core.data.event

import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.EventId
import kotlinx.coroutines.flow.Flow

/** The Event repository the UI depends on (ADR-0001, #71) — reads are local `Flow`s only. */
interface EventRepository {
    fun observeEvents(): Flow<List<Event>>
    fun observeEvent(id: EventId): Flow<Event?>
}

/** The offline-first [EventRepository]: the local store is the single source of truth (ADR-0001). */
class OfflineEventRepository(
    private val localStore: EventLocalStore,
) : EventRepository {
    override fun observeEvents(): Flow<List<Event>> = localStore.observeActive()
    override fun observeEvent(id: EventId): Flow<Event?> = localStore.observe(id)
}

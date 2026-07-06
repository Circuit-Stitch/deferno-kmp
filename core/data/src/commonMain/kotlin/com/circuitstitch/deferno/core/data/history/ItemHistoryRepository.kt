package com.circuitstitch.deferno.core.data.history

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.model.ItemHistoryEvent
import com.circuitstitch.deferno.core.network.DefernoJson
import com.circuitstitch.deferno.core.network.dto.TaskActionDto
import com.circuitstitch.deferno.core.network.dto.toTaskActionKind
import com.circuitstitch.deferno.core.network.mapper.toDomain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlin.time.Instant

/**
 * The offline-first Item-history repository (ADR-0043, #197). History is **read-only, server-authored,
 * complete, and durable**: the detail [observe]s it from the cache (ADR-0001) and a task-detail open
 * fires a best-effort [refresh] that **replaces** the item's cached rows wholesale. There is no outbox
 * guard (unlike comments) — the client never writes history, so a refresh can't clobber a local change;
 * append-only server-derived history makes a whole replace dedup-free (there is no stable event id). A
 * permanently-gone server degrades this to "no new history arrives" — the cache stands. v1 is Task-only.
 *
 * The repo owns the `payload` ↔ domain boundary (ADR-0043): a fetched [TaskActionDto]'s **raw wire kind**
 * is stored verbatim into [StoredHistoryRow.payload] for the cache, and a cached row decodes that raw
 * element back to a kind and condenses to the domain [ItemHistoryEvent] — keeping [ItemHistoryLocalStore]
 * free of any serialization coupling, and keeping an unknown kind recoverable (the server's bytes are kept).
 */
interface ItemHistoryRepository {

    /** The cached history for [itemId], oldest-first, as domain events (ADR-0001) — never the network. */
    fun observe(itemId: String): Flow<List<ItemHistoryEvent>>

    /** Best-effort on-open refresh; replaces the item's cached history, or no-ops if unreachable. */
    suspend fun refresh(itemId: String)

    companion object {
        /** An empty repository (no history, no-op refresh) — the default a component/shell builds over. */
        val NONE: ItemHistoryRepository = object : ItemHistoryRepository {
            override fun observe(itemId: String): Flow<List<ItemHistoryEvent>> = flowOf(emptyList())
            override suspend fun refresh(itemId: String) {}
        }
    }
}

/** The production [ItemHistoryRepository] over the local cache + the best-effort remote source. */
class DefaultItemHistoryRepository(
    private val localStore: ItemHistoryLocalStore,
    private val remoteSource: ItemHistoryRemoteSource,
) : ItemHistoryRepository {

    override fun observe(itemId: String): Flow<List<ItemHistoryEvent>> =
        localStore.observe(itemId).map { rows -> rows.map { it.toEvent() } }

    override suspend fun refresh(itemId: String) {
        val actions = when (val snapshot = remoteSource.fetchHistory(itemId)) {
            is RemoteSnapshot.Available -> snapshot.value
            RemoteSnapshot.Unavailable -> return
        }
        localStore.replaceForItem(itemId, actions.map { it.toStoredRow() })
    }
}

/** Store the fetched action's raw wire kind verbatim — no re-encode (ADR-0043; the client never writes
 *  history, so the cache keeps the server's own bytes and an unknown kind stays recoverable). */
private fun TaskActionDto.toStoredRow(): StoredHistoryRow =
    StoredHistoryRow(recordedAt = recordedAt, payload = kind.toString())

/** Decode a cached row's raw payload back to a kind and condense to the domain event. */
private fun StoredHistoryRow.toEvent(): ItemHistoryEvent =
    DefernoJson.parseToJsonElement(payload).toTaskActionKind(DefernoJson).toDomain(Instant.parse(recordedAt))

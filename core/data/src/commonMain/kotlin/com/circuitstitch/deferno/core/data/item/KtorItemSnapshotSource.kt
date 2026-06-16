package com.circuitstitch.deferno.core.data.item

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.map
import com.circuitstitch.deferno.core.network.mapper.asChoreOrNull
import com.circuitstitch.deferno.core.network.mapper.asEventOrNull
import com.circuitstitch.deferno.core.network.mapper.asHabitOrNull
import com.circuitstitch.deferno.core.network.mapper.asTaskOrNull
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.http.appendPathSegments

/**
 * The production [ItemSnapshotSource] over the shared Deferno [HttpClient] (ADR-0034, #226). It pulls
 * the polymorphic `GET /items` cold snapshot and condenses each `oneOf{task,habit,chore,event}`
 * element to its domain kind at the boundary via the #18 `ItemView` mappers, partitioning the
 * heterogeneous array into the four kind lists of an [ItemSnapshot] — so [ItemSync] never touches a DTO.
 *
 * Offline-first (ADR-0001): [asSnapshot] maps every [com.circuitstitch.deferno.core.network.ApiResult.Failure]
 * mode to [RemoteSnapshot.Unavailable] (never throws), distinct from an [RemoteSnapshot.Available]
 * empty snapshot.
 */
class KtorItemSnapshotSource(
    private val client: HttpClient,
) : ItemSnapshotSource {

    override suspend fun fetchAll(): RemoteSnapshot<ItemSnapshot> =
        client.requestApi<List<ItemView>> { get("items") }
            .map { items ->
                ItemSnapshot(
                    tasks = items.mapNotNull { it.asTaskOrNull() },
                    habits = items.mapNotNull { it.asHabitOrNull() },
                    chores = items.mapNotNull { it.asChoreOrNull() },
                    events = items.mapNotNull { it.asEventOrNull() },
                )
            }
            .asSnapshot()
}

/** Configures a `GET` to [segments] appended onto the client's base URL (CONTRACT-NOTES paths). */
private fun HttpRequestBuilder.get(vararg segments: String) {
    url { appendPathSegments(*segments) }
}

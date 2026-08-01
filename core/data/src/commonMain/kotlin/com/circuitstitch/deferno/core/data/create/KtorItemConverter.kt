package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.map
import com.circuitstitch.deferno.core.network.mapper.asChoreOrNull
import com.circuitstitch.deferno.core.network.mapper.asEventOrNull
import com.circuitstitch.deferno.core.network.mapper.asHabitOrNull
import com.circuitstitch.deferno.core.network.mapper.asTaskOrNull
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType

/**
 * The production [StampedItemConverter] over the shared Deferno [HttpClient]: `POST /items/{id}/convert`
 * (ADR-0016, the one write still online-only now that ADR-0049 has moved create onto the outbox). It uses
 * `requestApi<T>` — the response *is* interesting (the item's new kind seeds the local cache), unlike the
 * outbox sender's fire-and-forget replay — and returns an [ApiResult] so the writer can tell a
 * server-confirmed convert from the transport failure that surfaces as the gentle "reconnect to save". The
 * shared client's bearer-auth + cleartext guard fire per request, just like every call.
 *
 * It serves ONLY the stamped half and never [ItemConverter]: this class is a pure wire adapter, so the
 * Activity stamp is something it carries, never something it invents. Implementing the unstamped half here
 * too would hand any caller an unstamped convert straight onto the wire — the exact failure the split
 * exists to prevent. `internal` follows from that supertype — outside core:data the only convert type is
 * [ItemConverter].
 */
internal class KtorItemConverter(
    private val client: HttpClient,
) : StampedItemConverter {

    override suspend fun convert(id: String, payload: ConvertItemPayload, stamp: ActivityStamp): ApiResult<ConvertedItem> =
        client.requestApi<ItemView> {
            method = HttpMethod.Post
            url { appendPathSegments("items", id, "convert") }
            contentType(ContentType.Application.Json)
            // The stamp is a field on the payload, not a sibling spliced into an encoded copy of it: a body
            // assembled key-by-key drifts from the DTO the contract is pinned against, silently, the moment
            // either side changes. One @Serializable shape, one serializer.
            setBody(payload.copy(activity = stamp.toJson()))
        }.map { it.toConvertedItem() }
}

/** Maps the polymorphic convert response to the kind it became (exactly one matches). */
private fun ItemView.toConvertedItem(): ConvertedItem = when (this) {
    is ItemView.Task -> ConvertedItem.AsTask(asTaskOrNull()!!)
    is ItemView.Habit -> ConvertedItem.AsHabit(asHabitOrNull()!!)
    is ItemView.Chore -> ConvertedItem.AsChore(asChoreOrNull()!!)
    is ItemView.Event -> ConvertedItem.AsEvent(asEventOrNull()!!)
}

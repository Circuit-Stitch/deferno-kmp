package com.circuitstitch.deferno.core.data.create

import com.circuitstitch.deferno.core.model.Chore
import com.circuitstitch.deferno.core.model.Event
import com.circuitstitch.deferno.core.model.Habit
import com.circuitstitch.deferno.core.model.Task
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.ChoreDetailDto
import com.circuitstitch.deferno.core.network.dto.ConvertItemPayload
import com.circuitstitch.deferno.core.network.dto.CreateChorePayload
import com.circuitstitch.deferno.core.network.dto.CreateEventPayload
import com.circuitstitch.deferno.core.network.dto.CreateHabitPayload
import com.circuitstitch.deferno.core.network.dto.CreateTaskPayload
import com.circuitstitch.deferno.core.network.dto.EventDetailDto
import com.circuitstitch.deferno.core.network.dto.HabitDetailDto
import com.circuitstitch.deferno.core.network.dto.ItemView
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.mapper.asChoreOrNull
import com.circuitstitch.deferno.core.network.mapper.asEventOrNull
import com.circuitstitch.deferno.core.network.mapper.asHabitOrNull
import com.circuitstitch.deferno.core.network.mapper.asTaskOrNull
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType

/**
 * The production [ItemRemoteSource] over the shared Deferno [HttpClient] (#17/#71, ADR-0016). Each
 * create POSTs the matching create payload and condenses the full single-item response to the domain
 * entity at the boundary (ADR-0011), returning an [ApiResult] so the create writer can distinguish a
 * server-confirmed create from a transport/4xx failure (which surfaces as the gentle "reconnect to
 * save"). The shared client's bearer-auth + cleartext guard fire per request, just like every call.
 *
 * Create uses `requestApi<T>` (the envelope-unwrapping path) — the response *is* interesting (we need
 * the server-assigned id), unlike the outbox sender's fire-and-forget replay.
 */
class KtorItemRemoteSource(
    private val client: HttpClient,
) : ItemRemoteSource {

    override suspend fun createTask(payload: CreateTaskPayload): ApiResult<Task> =
        client.requestApi<TaskDetailDto> { post("tasks", payload) }.mapData { it.toDomain() }

    override suspend fun createHabit(payload: CreateHabitPayload): ApiResult<Habit> =
        client.requestApi<HabitDetailDto> { post("habits", payload) }.mapData { it.toDomain() }

    override suspend fun createChore(payload: CreateChorePayload): ApiResult<Chore> =
        client.requestApi<ChoreDetailDto> { post("chores", payload) }.mapData { it.toDomain() }

    override suspend fun createEvent(payload: CreateEventPayload): ApiResult<Event> =
        client.requestApi<EventDetailDto> { post("events", payload) }.mapData { it.toDomain() }

    override suspend fun convert(id: String, payload: ConvertItemPayload): ApiResult<ConvertedItem> =
        client.requestApi<ItemView> {
            method = HttpMethod.Post
            url { appendPathSegments("items", id, "convert") }
            contentType(ContentType.Application.Json)
            setBody(payload)
        }.mapData { it.toConvertedItem() }
}

/** Maps the polymorphic convert response to the kind it became (exactly one matches). */
private fun ItemView.toConvertedItem(): ConvertedItem = when (this) {
    is ItemView.Task -> ConvertedItem.AsTask(asTaskOrNull()!!)
    is ItemView.Habit -> ConvertedItem.AsHabit(asHabitOrNull()!!)
    is ItemView.Chore -> ConvertedItem.AsChore(asChoreOrNull()!!)
    is ItemView.Event -> ConvertedItem.AsEvent(asEventOrNull()!!)
}

/**
 * Configures a `POST` of [body] to a single [segment]. `reified T` so the body's serializer is the
 * payload's actual type (a non-reified `Any` would erase the type and ContentNegotiation couldn't
 * find a serializer); the JSON content type makes the shared client's negotiation encode it.
 */
private inline fun <reified T> HttpRequestBuilder.post(segment: String, body: T) {
    method = HttpMethod.Post
    url { appendPathSegments(segment) }
    contentType(ContentType.Application.Json)
    setBody(body)
}

/** Maps an [ApiResult] success payload through [transform], leaving a failure untouched. */
private inline fun <T, R> ApiResult<T>.mapData(transform: (T) -> R): ApiResult<R> = when (this) {
    is ApiResult.Success -> ApiResult.Success(transform(data))
    is ApiResult.Failure -> ApiResult.Failure(error)
}

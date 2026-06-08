package com.circuitstitch.deferno.core.data.calendar

import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.CalendarEventDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * The production [CalendarRemoteSource] over the shared Deferno [HttpClient] (#17/#18, ADR-0001).
 * `GET /tasks/calendar` takes the half-open `[start, end)` local-date window + tz and returns the flat
 * `CalendarEvent` feed; each row condenses to the clean [CalendarItem] at the edge (ADR-0011), with its
 * UTC `start` projected into [tz] to bucket onto a local day.
 *
 * Offline-first (ADR-0001): an [ApiResult.Failure] maps to `null`, so a failed refresh leaves the
 * cached window untouched. A malformed [tz] degrades to UTC rather than throwing (the shell always
 * supplies a valid IANA zone, so this is purely defensive).
 */
class KtorCalendarRemoteSource(
    private val client: HttpClient,
) : CalendarRemoteSource {

    override suspend fun fetchWindow(from: LocalDate, to: LocalDate, tz: String): List<CalendarItem>? {
        val result = client.requestApi<List<CalendarEventDto>> {
            url { appendPathSegments("tasks", "calendar") }
            parameter("start", from.toString())
            parameter("end", to.toString())
            parameter("tz", tz)
        }
        return when (result) {
            is ApiResult.Success -> {
                val zone = runCatching { TimeZone.of(tz) }.getOrDefault(TimeZone.UTC)
                result.data.map { it.toDomain(zone) }
            }
            is ApiResult.Failure -> null
        }
    }
}

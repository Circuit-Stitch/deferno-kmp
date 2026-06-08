package com.circuitstitch.deferno.core.data.settings

import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.UserSettingsDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments

/**
 * The production [SettingsRemoteSource] over the shared Deferno [HttpClient] (#72). It pulls
 * `GET /auth/me/settings` through the same `requestApi` pipeline every endpoint uses — the bearer
 * plugin (Active Account PAT), the tolerant reader, the envelope unwrap, the version gate — and
 * condenses the wire [UserSettingsDto] to the domain [UserSettings] at the boundary (ADR-0011).
 *
 * Offline-first (ADR-0001): an [ApiResult.Failure] maps to `null`, so a failed refresh leaves the
 * cached settings intact.
 */
class KtorSettingsRemoteSource(
    private val client: HttpClient,
) : SettingsRemoteSource {

    override suspend fun fetchSettings(): UserSettings? {
        val result = client.requestApi<UserSettingsDto> {
            url { appendPathSegments("auth", "me", "settings") }
        }
        return when (result) {
            is ApiResult.Success -> result.data.toDomain()
            is ApiResult.Failure -> null
        }
    }
}

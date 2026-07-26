package com.circuitstitch.deferno.core.data.activity

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.network.dto.ActivityFeedDto
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.parameter
import io.ktor.http.appendPathSegments

/**
 * The server Activity ledger's read port (`GET /activity`, #364).
 *
 * Only the **sync** axis is exposed. The feed axis (`?before=`, human scroll-back over `occurred_at`) is
 * deliberately absent: the Activity destination renders from the local cache, so scroll-back would be a
 * second, unused pagination path. Add it when the destination grows infinite scroll, not before.
 */
interface ActivityRemoteSource {

    /**
     * One page of entries observed after [since] — an opaque `next_since` token from a previous page, or a
     * bare RFC-3339 timestamp to bootstrap a first sync. Ordered `observed_at` **ascending** and gapless.
     *
     * Degrades to [RemoteSnapshot.Unavailable] on any failure, including the `503` an environment without
     * a configured ledger returns; the caller then leaves its cursor and cached rows untouched.
     */
    suspend fun sync(since: String, limit: Int): RemoteSnapshot<ActivityFeedDto>
}

/** The production [ActivityRemoteSource] over the shared authed Deferno [HttpClient]. */
class KtorActivityRemoteSource(
    private val client: HttpClient,
) : ActivityRemoteSource {

    override suspend fun sync(since: String, limit: Int): RemoteSnapshot<ActivityFeedDto> =
        client.requestApi<ActivityFeedDto> {
            url { appendPathSegments("activity") }
            parameter("since", since)
            parameter("limit", limit)
            // `before` is deliberately never sent alongside `since` — the server rejects the pair with a
            // 400 rather than silently dropping one, since an audit read must not quietly ignore a bound.
        }.asSnapshot()
}

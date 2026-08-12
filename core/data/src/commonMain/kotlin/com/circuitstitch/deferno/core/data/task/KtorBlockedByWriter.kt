package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.item.CachedItem
import com.circuitstitch.deferno.core.data.item.ItemLocalStore
import com.circuitstitch.deferno.core.model.BlockedByRef
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.plugin.Blocker
import com.circuitstitch.deferno.core.model.plugin.replacingFamilyOf
import com.circuitstitch.deferno.core.network.ApiError
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.BlockedByRefDto
import com.circuitstitch.deferno.core.network.dto.SetBlockedByPayload
import com.circuitstitch.deferno.core.network.dto.TaskDetailDto
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType

/**
 * The production [BlockedByWriter] (#291): optimistic-apply → `PATCH tasks/{id}` → revert-on-rejection.
 *
 * Mirrors the ConvertItem online-only posture ([OfflineCreateWriter.convert], ADR-0016) rather than the
 * outbox: the server is the sole validator of the edge set (a dependency cycle or a cross-org edge is a
 * `400`), and the client holds no full edge graph to pre-validate against — so the verdict must come
 * back *now*, as a structured [BlockedByResult] the menu surfaces, not as a silently-dropped replay.
 *
 * The optimistic apply sets the cached row's `blockedBy` plus a provisional `blocked` flag
 * (`blockers.isNotEmpty()` — the flag is really server-derived and also inherits from a blocked
 * ancestor, so the next `/items` reconcile is the authority; this only keeps the tree honest for the
 * seconds in between). The PATCH response body is deliberately **discarded**: the `/tasks/{id}` detail
 * omits the derived `blocked`/`is_blocker` flags, so upserting it would clobber the cached truth.
 */
class KtorBlockedByWriter(
    private val client: HttpClient,
    private val connectivity: Connectivity,
    private val items: ItemLocalStore,
) : BlockedByWriter {

    override suspend fun setBlockedBy(id: TaskId, blockers: List<BlockedByRef>): BlockedByResult {
        if (!connectivity.isOnline()) return BlockedByResult.Offline

        // Optimistic apply, capturing the pre-edit row the revert restores (same transaction seam the
        // reconcile uses, so this read-modify-write can't interleave with one). An uncached row still
        // PATCHes (the write isn't lost) — there is just nothing to apply or revert locally.
        var previous: CachedItem? = null
        items.transaction { store ->
            previous = store.get(id.value)?.also { current -> store.upsert(current.withBlockers(blockers)) }
        }

        val result = client.requestApi<TaskDetailDto> {
            method = HttpMethod.Patch
            url { appendPathSegments("tasks", id.value) }
            contentType(ContentType.Application.Json)
            setBody(SetBlockedByPayload(blockers.map { BlockedByRefDto(it.item, it.occurrence) }))
        }
        return when (result) {
            is ApiResult.Success -> BlockedByResult.Applied
            is ApiResult.Failure -> {
                previous?.let { prev -> items.transaction { store -> store.upsert(prev) } }
                result.error.toResult()
            }
        }
    }

    /**
     * The optimistic edge set, as a Linkage swap: unload whatever [Blocker] is there and load one
     * carrying the new edges (#422). It was a two-field `copy` on a `Task` before the cache went
     * plugin-shaped, and it is the same edit either way — the difference is that the record it applies
     * to names no kind, so nothing here has to be a Task.
     *
     * `isBlocker` is carried across untouched: it says this row gates something *else*, which is not
     * what was edited.
     */
    private fun CachedItem.withBlockers(blockers: List<BlockedByRef>): CachedItem = copy(
        item = item.copy(
            plugins = item.plugins.replacingFamilyOf(
                Blocker(
                    blocked = blockers.isNotEmpty(),
                    isBlocker = item.blocker.isBlocker,
                    blockedBy = blockers,
                ),
            ),
        ),
    )

    /** Transport failure → the gentle Offline; any server verdict → Failed (retrying won't help). */
    private fun ApiError.toResult(): BlockedByResult = when (this) {
        is ApiError.Transport -> BlockedByResult.Offline
        is ApiError.Endpoint -> BlockedByResult.Failed(message)
        is ApiError.Status -> BlockedByResult.Failed(message)
        is ApiError.UnsupportedVersion -> BlockedByResult.Failed("Unsupported API version: $version")
    }
}

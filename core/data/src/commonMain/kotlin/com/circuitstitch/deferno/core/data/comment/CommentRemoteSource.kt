package com.circuitstitch.deferno.core.data.comment

import com.circuitstitch.deferno.core.data.RemoteSnapshot
import com.circuitstitch.deferno.core.data.asSnapshot
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.dto.CommentDto
import com.circuitstitch.deferno.core.network.dto.CommentListResponseDto
import com.circuitstitch.deferno.core.network.map
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.url
import io.ktor.http.appendPathSegments

/**
 * The best-effort server read for a Task's comment thread (ADR-0043, #197) — the enrichment half of the
 * offline-first [CommentRepository]. Mirrors [com.circuitstitch.deferno.core.data.plan.PlanRemoteSource]:
 * a success is an [RemoteSnapshot.Available] list, and every failure collapses to
 * [RemoteSnapshot.Unavailable] so a refresh that can't reach the server leaves the cache untouched
 * (offline-first). Server-tombstoned comments are dropped here, so the reconcile can treat "absent from
 * the snapshot" uniformly as "gone server-side".
 */
interface CommentRemoteSource {

    /** `GET /tasks/{id}/comments` — the live (non-deleted) thread, or [RemoteSnapshot.Unavailable] on any failure. */
    suspend fun fetchComments(taskId: TaskId): RemoteSnapshot<List<Comment>>
}

/** The production [CommentRemoteSource] over the shared authed Deferno [HttpClient] (mirrors the other Ktor sources). */
class KtorCommentRemoteSource(
    private val client: HttpClient,
) : CommentRemoteSource {

    override suspend fun fetchComments(taskId: TaskId): RemoteSnapshot<List<Comment>> =
        client.requestApi<CommentListResponseDto> {
            url { appendPathSegments("tasks", taskId.value, "comments") }
        }
            .map { response -> response.comments.filter { it.deletedAt == null }.map(CommentDto::toDomain) }
            .asSnapshot()
}

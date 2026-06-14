package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.dto.AttachmentViewDto
import com.circuitstitch.deferno.core.network.dto.AuthenticatedUserDto
import com.circuitstitch.deferno.core.network.dto.CommentDto
import com.circuitstitch.deferno.core.network.dto.CommentListResponseDto
import com.circuitstitch.deferno.core.network.dto.CreateCommentPayload
import com.circuitstitch.deferno.core.network.dto.UpdateCommentPayload
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType

/**
 * The production [TaskDetailRepository] over the shared authed Deferno [HttpClient] — online-only
 * (ADR-0001), mirroring [KtorTaskRemoteSource.search]. Each call condenses the wire DTO to the domain
 * type at the boundary (ADR-0011); a failure degrades to `null`/`false` rather than throwing, so the
 * detail can show an inline "couldn't load" / "couldn't post" state without crashing.
 */
class KtorTaskDetailRepository(
    private val client: HttpClient,
) : TaskDetailRepository {

    override suspend fun comments(taskId: TaskId): List<Comment>? {
        val result = client.requestApi<CommentListResponseDto> {
            url { appendPathSegments("tasks", taskId.value, "comments") }
        }
        return when (result) {
            // Drop server-side soft-deleted comments before they reach the UI.
            is ApiResult.Success -> result.data.comments.filter { it.deletedAt == null }.map { it.toDomain() }
            is ApiResult.Failure -> null
        }
    }

    override suspend fun postComment(taskId: TaskId, body: String): Boolean {
        val result = client.requestApi<CommentDto> {
            method = HttpMethod.Post
            url { appendPathSegments("tasks", taskId.value, "comments") }
            contentType(ContentType.Application.Json)
            setBody(CreateCommentPayload(body = body))
        }
        return result is ApiResult.Success
    }

    override suspend fun editComment(commentId: String, body: String): Boolean {
        val result = client.requestApi<CommentDto> {
            method = HttpMethod.Patch
            url { appendPathSegments("comments", commentId) }
            contentType(ContentType.Application.Json)
            setBody(UpdateCommentPayload(body = body))
        }
        return result is ApiResult.Success
    }

    override suspend fun deleteComment(commentId: String): Boolean {
        val result = client.requestApi<CommentDto> {
            method = HttpMethod.Delete
            url { appendPathSegments("comments", commentId) }
        }
        return result is ApiResult.Success
    }

    override suspend fun attachments(taskId: TaskId): List<Attachment>? {
        val result = client.requestApi<List<AttachmentViewDto>> {
            url { appendPathSegments("tasks", taskId.value, "attachments") }
        }
        return when (result) {
            is ApiResult.Success -> result.data.map { it.toDomain() }
            is ApiResult.Failure -> null
        }
    }

    override suspend fun currentUserId(): UserId? {
        val result = client.requestApi<AuthenticatedUserDto> {
            url { appendPathSegments("auth", "me") }
        }
        return when (result) {
            is ApiResult.Success -> result.data.toDomain().id
            is ApiResult.Failure -> null
        }
    }
}

package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.Comment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.UploadHttpClient
import com.circuitstitch.deferno.core.network.dto.AttachmentIntentDto
import com.circuitstitch.deferno.core.network.dto.AttachmentPresignBatchRequestDto
import com.circuitstitch.deferno.core.network.dto.AttachmentPresignBatchResponseDto
import com.circuitstitch.deferno.core.network.dto.AttachmentViewDto
import com.circuitstitch.deferno.core.network.dto.AuthenticatedUserDto
import com.circuitstitch.deferno.core.network.dto.CommentDto
import com.circuitstitch.deferno.core.network.dto.CommentListResponseDto
import com.circuitstitch.deferno.core.network.dto.CommitAttachmentsPayload
import com.circuitstitch.deferno.core.network.dto.CreateCommentPayload
import com.circuitstitch.deferno.core.network.dto.PresignRequestDto
import com.circuitstitch.deferno.core.network.dto.PresignResponseDto
import com.circuitstitch.deferno.core.network.dto.UpdateCommentPayload
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The production [TaskDetailRepository] over the shared authed Deferno [HttpClient] — online-only
 * (ADR-0001), mirroring [KtorTaskRemoteSource.search]. Each call condenses the wire DTO to the domain
 * type at the boundary (ADR-0011); a failure degrades to `null`/`false` rather than throwing, so the
 * detail can show an inline "couldn't load" / "couldn't post" state without crashing.
 */
class KtorTaskDetailRepository(
    private val client: HttpClient,
    // The bare client (no base URL, no auth) for the presigned PUTs — an Authorization header would
    // break S3 SigV4 (see [UploadHttpClient]). Defaults to a non-uploading no-op so the comment-only
    // tests can still construct the repo with just `client`.
    private val uploadClient: UploadHttpClient? = null,
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

    override suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>): Boolean {
        if (files.isEmpty()) return true
        val upload = uploadClient ?: return false

        // 1. Presign the batch — one signed PUT URL per file, in request order.
        val presign = client.requestApi<AttachmentPresignBatchResponseDto> {
            method = HttpMethod.Post
            url { appendPathSegments("tasks", taskId.value, "attachments", "presign") }
            contentType(ContentType.Application.Json)
            setBody(
                AttachmentPresignBatchRequestDto(
                    files = files.map { PresignRequestDto(it.filename, it.contentType, it.bytes.size.toLong()) },
                ),
            )
        }
        val presigned = when (presign) {
            is ApiResult.Success -> presign.data.attachments
            is ApiResult.Failure -> return false
        }
        if (presigned.size != files.size) return false

        // 2. PUT each file byte-exact to its presigned URL (the list is parallel to `files`).
        presigned.forEachIndexed { i, p ->
            if (!put(upload, p, files[i])) return false
        }

        // 3. Commit the uploaded ids onto the Task.
        val commit = client.requestApi<List<AttachmentViewDto>> {
            method = HttpMethod.Post
            url { appendPathSegments("tasks", taskId.value, "attachments") }
            contentType(ContentType.Application.Json)
            setBody(CommitAttachmentsPayload(intents = presigned.map { AttachmentIntentDto(it.attachmentId) }))
        }
        return commit is ApiResult.Success
    }

    /** PUT one file's bytes to its presigned URL, sending the signed [presigned] `headers` byte-exact. */
    private suspend fun put(upload: UploadHttpClient, presigned: PresignResponseDto, file: AttachmentUpload): Boolean {
        val response: HttpResponse = try {
            upload.client.put(presigned.putUrl) {
                var contentTypeSet = false
                for ((k, v) in presigned.headers) {
                    if (k.equals(HttpHeaders.ContentType, ignoreCase = true)) {
                        runCatching { contentType(ContentType.parse(v)) }.onSuccess { contentTypeSet = true }
                    } else {
                        header(k, v)
                    }
                }
                // LocalFs (dev) signs no content-type; fall back to the file's own so the body type is right.
                if (!contentTypeSet) runCatching { contentType(ContentType.parse(file.contentType)) }
                setBody(file.bytes)
            }
        } catch (t: Throwable) {
            return false
        }
        return response.status.isSuccess()
    }

    override suspend fun updateAttachmentCaption(taskId: TaskId, attachmentId: String, caption: String?): Boolean {
        val result = client.requestApi<AttachmentViewDto> {
            method = HttpMethod.Patch
            url { appendPathSegments("tasks", taskId.value, "attachments", attachmentId) }
            contentType(ContentType.Application.Json)
            // #416: hand-build the body so a null clear is emitted explicitly as `caption: null`.
            // The shared DefernoJson (explicitNulls = false) would drop a null on a typed payload,
            // reaching the server as an omitted field it rejects (422). A JsonObject's JsonNull is
            // serialized structurally, independent of explicitNulls.
            setBody(buildJsonObject { put("caption", caption) })
        }
        return result is ApiResult.Success
    }

    // Bypasses `requestApi` deliberately: this DELETE replies 204 No Content, whose empty body the
    // version-probe would treat as malformed (cf. the auth-token revoke). Check the status directly.
    override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String): Boolean = try {
        val response = client.delete {
            url { appendPathSegments("tasks", taskId.value, "attachments", attachmentId) }
        }
        response.status.isSuccess()
    } catch (t: Throwable) {
        false
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

package com.circuitstitch.deferno.core.data.task

import com.circuitstitch.deferno.core.data.activity.ActivityStamp
import com.circuitstitch.deferno.core.model.Attachment
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.network.ApiResult
import com.circuitstitch.deferno.core.network.UploadHttpClient
import com.circuitstitch.deferno.core.network.dto.AttachmentPresignBatchRequestDto
import com.circuitstitch.deferno.core.network.dto.AttachmentPresignBatchResponseDto
import com.circuitstitch.deferno.core.network.dto.AttachmentViewDto
import com.circuitstitch.deferno.core.network.dto.PresignRequestDto
import com.circuitstitch.deferno.core.network.dto.PresignResponseDto
import com.circuitstitch.deferno.core.network.mapper.toDomain
import com.circuitstitch.deferno.core.network.requestApi
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.appendPathSegments
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

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

    override suspend fun attachments(taskId: TaskId): List<Attachment>? {
        val result = client.requestApi<List<AttachmentViewDto>> {
            url { appendPathSegments("items", taskId.value, "attachments") }
        }
        return when (result) {
            is ApiResult.Success -> result.data.map { it.toDomain() }
            is ApiResult.Failure -> null
        }
    }

    override suspend fun uploadAttachments(taskId: TaskId, files: List<AttachmentUpload>, stamp: ActivityStamp?): Boolean {
        if (files.isEmpty()) return true
        val upload = uploadClient ?: return false

        // 1. Presign the batch — one signed PUT URL per file, in request order.
        val presign = client.requestApi<AttachmentPresignBatchResponseDto> {
            method = HttpMethod.Post
            url { appendPathSegments("items", taskId.value, "attachments", "presign") }
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

        // 3. Commit the uploaded ids onto the item. Only this step mints a ledger entry server-side — the
        //    presign handshake and the S3 PUTs are not item mutations — so the stamp rides here alone.
        val commit = client.requestApi<List<AttachmentViewDto>> {
            method = HttpMethod.Post
            url { appendPathSegments("items", taskId.value, "attachments") }
            contentType(ContentType.Application.Json)
            // Hand-built rather than the typed CommitAttachmentsPayload: `activity` is an untyped sibling
            // (core:data has no serialization compiler plugin, so the stamp is a raw JsonObject), and a
            // body must be one shape or the other. JSON it is — the same call the typed payload rendered.
            setBody(
                buildJsonObject {
                    putJsonArray("intents") {
                        presigned.forEach { p -> addJsonObject { put("attachment_id", p.attachmentId) } }
                    }
                    stamp?.let { put("activity", it.toJson()) }
                },
            )
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

    override suspend fun updateAttachmentCaption(
        taskId: TaskId,
        attachmentId: String,
        caption: String?,
        stamp: ActivityStamp?,
    ): Boolean {
        val result = client.requestApi<AttachmentViewDto> {
            method = HttpMethod.Patch
            url { appendPathSegments("items", taskId.value, "attachments", attachmentId) }
            contentType(ContentType.Application.Json)
            // #416: hand-build the body so a null clear is emitted explicitly as `caption: null`.
            // The shared DefernoJson (explicitNulls = false) would drop a null on a typed payload,
            // reaching the server as an omitted field it rejects (422). A JsonObject's JsonNull is
            // serialized structurally, independent of explicitNulls.
            setBody(
                buildJsonObject {
                    put("caption", caption)
                    stamp?.let { put("activity", it.toJson()) }
                },
            )
        }
        return result is ApiResult.Success
    }

    // A POST soft-delete, not a DELETE (#364): the backend retired `DELETE /tasks/{id}/attachments/{aid}`
    // so ledger metadata could ride in a body, and left no alias. Still bypasses `requestApi`
    // deliberately: the reply is 204 No Content, whose empty body the version-probe would treat as
    // malformed (cf. the auth-token revoke). Check the status directly.
    override suspend fun deleteAttachment(taskId: TaskId, attachmentId: String, stamp: ActivityStamp?): Boolean = try {
        val response = client.post {
            url { appendPathSegments("items", taskId.value, "attachments", attachmentId, "delete") }
            contentType(ContentType.Application.Json)
            // ActivityBody: every field optional, so an empty object is a valid delete.
            setBody(buildJsonObject { stamp?.let { put("activity", it.toJson()) } })
        }
        response.status.isSuccess()
    } catch (t: Throwable) {
        false
    }
}
